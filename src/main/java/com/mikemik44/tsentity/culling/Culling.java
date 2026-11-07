package com.mikemik44.tsentity.culling;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Fits item displays' culling boxes (display width / height) to their custom models. A display's box is square from
 * above, centred on the entity, and runs from its feet up `height` blocks; with both at 0 it's never culled, which is
 * what costs frames with many props. The plugin reads the model from the resource pack, puts it through the same
 * transforms the client does (the model's display transform for the display's context, the entity's transformation,
 * its yaw/pitch) and sets the smallest box that holds every corner.
 */
public final class Culling implements Listener {

    final JavaPlugin plugin;

    public Culling(JavaPlugin plugin) {
        this.plugin = plugin;
    }


    /**
     * The client pads every entity's culling box by this much on every side before it checks the screen
     * (`inflate(0.5)`), so a display's real box is width + 1 across and runs from 0.5 below it to height + 0.5 above
     * it. Nathan's rule: a 3-wide model needs width 2.
     */
    public static final float PAD = 0.5f;
    /** Width or height 0 means "never cull": the smallest size that still culls (Nathan: 0.0001). */
    public static final float MIN = 0.0001f;

    /** The `/tsentity` subcommands this part answers. */
    public static final List<String> SUBCOMMANDS = List.of("look", "near", "center", "centre", "shift", "fit", "ignore",
            "feet", "report", "reload");

    /** What a fit found: the box, and anything the box can't cover. */
    public record Fit(float width, float height, float below, float centerX, float centerZ, String model, List<String> notes,
               String detail) {}

    /**
     * Displays another part just placed exactly where they belong (frame conversion): their first fit only sizes the
     * box, it never centres or shifts them.
     */
    public static final java.util.Set<java.util.UUID> placedExactly = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Models models;
    /** On a display this plugin looks after: what it was last fitted for. A change in it means fit again. */
    NamespacedKey fittedFor;

    /** The pack reader (item definitions, models, tints). */
    public Models models() {
        return models;
    }

    /** Called from TSEntity's onEnable (after its saveDefaultConfig). */
    public void enable() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        fittedFor = new NamespacedKey(plugin, "fitted_for");
        // editors (Axiom, Display Entity Editor) place a display first and scale/turn/retexture it after: follow along
        Bukkit.getScheduler().runTaskTimer(plugin, this::refitChanged, 20, 10);
        long every = Math.max(0, plugin.getConfig().getLong("pack-refresh-minutes", 30)) * 60 * 20;
        if (every > 0) Bukkit.getScheduler().runTaskTimer(plugin, () -> refreshPacks(null), every, every);
    }

    /** What decides a display's box: its item, mode, billboard, rotation and transformation. */
    static String signature(ItemDisplay d) {
        ItemStack item = d.getItemStack();
        return (item == null ? "" : item.getType() + String.valueOf(item.getData(DataComponentTypes.ITEM_MODEL))
                + item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)) + "|" + look(d);
    }

    /** The signature without the item: mode, billboard, rotation and transformation. */
    static String look(ItemDisplay d) {
        Location l = d.getLocation();
        return d.getItemDisplayTransform() + "|" + d.getBillboard() + "|" + l.getYaw() + "|" + l.getPitch() + "|" + d.getTransformation();
    }

    /** Fit again every display this plugin fitted whose look has changed since. Ones it never fitted are left alone. */
    void refitChanged() {
        if (!plugin.getConfig().getBoolean("auto", true)) return;
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                String was = d.getPersistentDataContainer().get(fittedFor, PersistentDataType.STRING);
                if (was != null && !was.equals(signature(d))) {
                    // only its item changed (a door swinging open, a sink filling: a plugin showing another model): the
                    // entity moves to the new model, as when placed, so nothing shifts on screen (2026-11-01: with
                    // `stay` the model jumped by the difference between the two models' middles). A rescale or a turn
                    // keeps the entity and centres the model on it.
                    boolean onlyItem = was.endsWith("|" + look(d));
                    apply(d, plugin.getConfig().getBoolean("shift-below", false), plugin.getConfig().getBoolean("shift-sideways", false),
                            !onlyItem);
                }
            }
        }
    }

    /**
     * Read the config and the packs. A pack is a folder, a .zip, or a URL (TS updates its pack on Dropbox): a URL is
     * downloaded in the background to the plugin's folder and read from there; until the first download finishes
     * (or when it fails) the last downloaded copy is used.
     */
    void load() {
        load(null);
    }

    void load(CommandSender tell) {
        plugin.reloadConfig();
        List<File> packs = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String p : plugin.getConfig().getStringList("packs")) {
            if (p.startsWith("http://") || p.startsWith("https://")) {
                urls.add(p);
                packs.add(cached(p));
                continue;
            }
            File f = new File(p);
            if (!f.exists()) plugin.getLogger().warning("pack not found: " + p);
            packs.add(f);
        }
        models = new Models(packs);
        if (!urls.isEmpty()) download(urls, tell);
    }

    /** Where a URL's pack is kept: plugins/TSEntity/packs/<hash of the URL>.zip. */
    File cached(String url) {
        return new File(new File(plugin.getDataFolder(), "packs"), Integer.toHexString(url.hashCode()) + ".zip");
    }

    /**
     * Fetch the URL packs off the main thread (Dropbox's dl=1 links redirect: followed). A new copy replaces the old
     * one only once it's complete and is a zip; then the models are read again on the main thread.
     */
    void download(List<String> urls, CommandSender tell) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(20)).build();
            List<String> results = new ArrayList<>();
            boolean changed = false;
            for (String url : urls) {
                File target = cached(url);
                File part = new File(target.getPath() + ".part");
                try {
                    target.getParentFile().mkdirs();
                    var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofMinutes(5)).GET().build();
                    var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofFile(part.toPath()));
                    if (res.statusCode() != 200) throw new java.io.IOException("HTTP " + res.statusCode());
                    try (var z = new java.util.zip.ZipFile(part)) {
                        if (z.getEntry("pack.mcmeta") == null && z.stream().noneMatch(e -> e.getName().endsWith("/pack.mcmeta"))) {
                            throw new java.io.IOException("not a resource pack (no pack.mcmeta)");
                        }
                    }
                    java.nio.file.Files.move(part.toPath(), target.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    changed = true;
                    results.add(String.format(Locale.ROOT, "pack downloaded (%.1f MB)", target.length() / 1048576.0));
                } catch (Exception e) {
                    part.delete();
                    results.add("pack download failed (" + e.getMessage() + ")"
                            + (target.isFile() ? ", using the last copy" : ", no copy yet"));
                }
            }
            boolean reread = changed;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (reread) models.clear();
                for (String r : results) {
                    plugin.getLogger().info(r);
                    if (tell != null) tell.sendMessage(Component.text(r, NamedTextColor.YELLOW));
                }
            });
        });
    }

    /** Fetch the URL packs again now (/tsentity reload, and every pack-refresh-minutes). */
    void refreshPacks(CommandSender tell) {
        List<String> urls = plugin.getConfig().getStringList("packs").stream()
                .filter(p -> p.startsWith("http://") || p.startsWith("https://")).toList();
        if (!urls.isEmpty()) download(urls, tell);
    }

    // automatic -----------------------------------------------------------------------------------------------------

    /** New displays only (summoned, placed, spawned by a plugin): ones loading in with their chunk are left as they are. */
    @EventHandler
    public void onSpawn(EntitySpawnEvent ev) {
        if (!(ev.getEntity() instanceof ItemDisplay d) || !plugin.getConfig().getBoolean("auto", true)) return;
        // next tick: a summon or a plugin may still be filling in the item and transformation
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!d.isValid()) return;
            // converted from an item frame: never shifted down, but centred unless center-frames is off (Nathan,
            // 2026-11-06: centring off by default, except for what was originally an item frame)
            if (placedExactly.remove(d.getUniqueId())) {
                apply(d, false, plugin.getConfig().getBoolean("center-frames", true));
                return;
            }
            if (plugin.getConfig().getBoolean("keep-manual", false) && (d.getDisplayWidth() != 0 || d.getDisplayHeight() != 0)) return;
            apply(d);
        });
    }

    /** Fit one display. Null when its item has no model the packs know. */
    public Fit apply(ItemDisplay d) {
        return apply(d, plugin.getConfig().getBoolean("shift-below", false), plugin.getConfig().getBoolean("shift-sideways", false));
    }

    /**
     * Fit one display; with `shift`, a model hanging below the entity is fixed first: the entity moves down by the
     * overhang and its transformation lifts the model back up by the same amount, so it looks the same but now sits
     * on top of the entity, where the box can reach it.
     */
    public Fit apply(ItemDisplay d, boolean shift, boolean center) {
        return apply(d, shift, center, false);
    }

    /**
     * `stay`: the entity keeps its place and only the model moves to be centred on it. Used after an edit (rescale,
     * turn), so the model grows or shrinks around where it was instead of drifting toward its origin (Nathan's ask);
     * on placement the entity moves to the model instead, so nothing shifts on screen.
     */
    Fit apply(ItemDisplay d, boolean shift, boolean center, boolean stay) {
        Fit f = fit(d);
        if (f == null) return null;
        // a passenger sits where its vehicle puts it: it can't be moved, so it's never centred or shifted
        if (ignored(d) || d.isInsideVehicle()) { shift = false; center = false; }
        // sideways: the box is a square around the entity, so a model off to one side makes it wider than the model.
        // Move the entity to the model's middle and the model back by the same amount (in the entity's own turned frame)
        if (center && Math.hypot(f.centerX(), f.centerZ()) > 0.01 && Math.abs(d.getLocation().getPitch()) < 0.01) {
            Vector3f local = new Vector3f(f.centerX(), 0, f.centerZ())
                    .rotate(new Quaternionf().rotationY(rad(d.getLocation().getYaw())));
            Transformation t = d.getTransformation();
            d.setTransformation(new Transformation(new Vector3f(t.getTranslation()).sub(local),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            if (!stay) move(d, f.centerX(), 0, f.centerZ());
            f = fit(d);
            if (f == null) return null;
        }
        if (shift && f.below() > 0.01 && Math.abs(d.getLocation().getPitch()) < 0.01) {
            Transformation t = d.getTransformation();
            Vector3f lifted = new Vector3f(t.getTranslation()).add(0, f.below(), 0);
            d.setTransformation(new Transformation(lifted, t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            move(d, 0, -f.below(), 0);
            f = fit(d);
            if (f == null) return null;
        }
        d.setDisplayWidth(f.width());
        d.setDisplayHeight(f.height());
        d.getPersistentDataContainer().set(fittedFor, PersistentDataType.STRING, signature(d));
        return f;
    }

    /** The model an item shows (after its custom model data is read): the name the ignore list uses. */
    public String modelKey(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Key key = item.getData(DataComponentTypes.ITEM_MODEL);
        String id = key != null ? key.asString() : "minecraft:" + item.getType().getKey().getKey();
        var data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        Models.Cmd cmd = data == null ? Models.Cmd.NONE : new Models.Cmd(data.floats(), data.flags(), data.strings());
        Models.Shape shape = models.item(id, cmd);
        return shape == null || shape.models().isEmpty() ? id : String.join("+", shape.models());
    }

    /** Models on the ignore list keep their offset: no centring or shifting, only the box is fitted. */
    boolean ignored(ItemDisplay d) {
        String k = modelKey(d.getItemStack());
        return k != null && plugin.getConfig().getStringList("ignore-offset").contains(k);
    }

    /** Move the entity without the client gliding it there. */
    static void move(ItemDisplay d, double dx, double dy, double dz) {
        int dur = d.getTeleportDuration();
        d.setTeleportDuration(0);
        d.teleport(d.getLocation().add(dx, dy, dz));
        d.setTeleportDuration(dur);
    }

    /** A model's own display transform for a mode (identity for NONE, or when the model sets none for it). */
    static Matrix4f modeMatrix(Models.Shape shape, String context) {
        JsonObject t = shape.display().get(context);
        Matrix4f item3 = new Matrix4f();
        if (t != null && !context.equals("none")) {
            float[] tr = t.has("translation") ? Models.floats(t.getAsJsonArray("translation")) : new float[3];
            float[] rot = t.has("rotation") ? Models.floats(t.getAsJsonArray("rotation")) : new float[3];
            float[] sc = t.has("scale") ? Models.floats(t.getAsJsonArray("scale")) : new float[]{1, 1, 1};
            item3.translate(clamp(tr[0], 80) / 16f, clamp(tr[1], 80) / 16f, clamp(tr[2], 80) / 16f)
                    .rotate(new Quaternionf().rotationXYZ(rad(rot[0]), rad(rot[1]), rad(rot[2])))
                    .scale(clamp(sc[0], 4), clamp(sc[1], 4), clamp(sc[2], 4));
        }
        return item3;
    }

    /**
     * What sits between the display's transformation and the model's centred geometry for this item in this mode:
     * the item display's 180° turn, then the model's display transform for the mode. Lets variant conversion compare
     * models drawn in different modes (fixed vs none).
     */
    public Matrix4f itemMatrix(ItemStack item, ItemDisplay.ItemDisplayTransform mode) {
        Matrix4f m = new Matrix4f().rotateY((float) Math.PI);
        if (item == null || item.getType().isAir()) return m;
        Key key = item.getData(DataComponentTypes.ITEM_MODEL);
        String id = key != null ? key.asString() : "minecraft:" + item.getType().getKey().getKey();
        var data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        Models.Cmd cmd = data == null ? Models.Cmd.NONE : new Models.Cmd(data.floats(), data.flags(), data.strings());
        Models.Shape shape = models.item(id, cmd);
        return shape == null ? m : m.mul(modeMatrix(shape, mode.name().toLowerCase(Locale.ROOT)));
    }

    /**
     * Models that stand on tilted feet (rotated cubes as legs): their lowest point is a leg's corner, so resting that on
     * the floor leaves the flat feet floating. These may sink until each leg's highest bottom corner is on the floor.
     * Automatic for anything named seating or chair, plus the feet-models list (/tsentity feet add|remove|list).
     */
    public boolean feet(ItemStack item) {
        String k = modelKey(item);
        if (k == null) return false;
        String low = k.toLowerCase(Locale.ROOT);
        return low.contains("seating") || low.contains("chair") || plugin.getConfig().getStringList("feet-models").contains(k);
    }

    /** The corners of an item's model in model pixels (0..16 for a full block), or null when the packs don't know it. */
    public List<Vector3f> corners(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Key key = item.getData(DataComponentTypes.ITEM_MODEL);
        String id = key != null ? key.asString() : "minecraft:" + item.getType().getKey().getKey();
        var data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        Models.Cmd cmd = data == null ? Models.Cmd.NONE : new Models.Cmd(data.floats(), data.flags(), data.strings());
        Models.Shape shape = models.item(id, cmd);
        return shape == null || shape.corners().isEmpty() ? null : shape.corners();
    }

    Fit fit(ItemDisplay d) {
        ItemStack item = d.getItemStack();
        if (item == null || item.getType().isAir()) return null;
        Key key = item.getData(DataComponentTypes.ITEM_MODEL);
        String id = key != null ? key.asString() : "minecraft:" + item.getType().getKey().getKey();
        var data = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        Models.Cmd cmd = data == null ? Models.Cmd.NONE : new Models.Cmd(data.floats(), data.flags(), data.strings());
        Models.Shape shape = models.item(id, cmd);
        if (shape == null) return null;

        // the model's display transform for this display's context (none for NONE)
        String context = d.getItemDisplayTransform().name().toLowerCase(Locale.ROOT);
        Matrix4f m = entityMatrix(d);
        Matrix4f item3 = modeMatrix(shape, context);
        m.mul(item3).translate(-0.5f, -0.5f, -0.5f).scale(1 / 16f);   // model units, centred like an item

        float maxH = 0, maxY = 0, minY = Float.MAX_VALUE, maxR = 0;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        Vector3f v = new Vector3f();
        for (Vector3f c : shape.corners()) {
            m.transformPosition(c, v);
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
            maxH = Math.max(maxH, Math.max(Math.abs(v.x), Math.abs(v.z)));
            maxY = Math.max(maxY, v.y);
            minY = Math.min(minY, v.y);
            maxR = Math.max(maxR, v.length());
        }
        List<String> notes = new ArrayList<>(shape.problems());
        float margin = (float) plugin.getConfig().getDouble("margin", 0.05);
        float width, height;
        if (d.getBillboard() != Display.Billboard.FIXED) {
            // it turns to face each viewer: the box has to hold the model at every angle
            width = 2 * maxR;
            height = maxR;
            notes.add("billboard " + d.getBillboard().name().toLowerCase(Locale.ROOT) + ": sized for every angle");
        } else if (plugin.getConfig().getBoolean("assume-positioned", true) && !d.isInsideVehicle()) {
            // (riding displays skip this: they're placed by their translation around the vehicle's spot, so their box
            // has to reach the model where it really is, rotation and all; see the branch below)
            // Nathan's rule: the builder places the model properly, so size it as if it already were: centred on X/Z
            // (its own span, not its reach from the entity) and, if it sinks into the floor, with its bottom 0.5 below
            // the entity (its own height). A model already in place gives the same numbers either way.
            width = Math.max(maxX - minX, maxZ - minZ);
            // ...unless it stays off-centre (centring is off by default since 2026-11-06): the square box is centred on
            // the entity, so it has to reach the model's far side or the model is culled while still on screen (Nathan)
            if (Math.hypot((minX + maxX) / 2, (minZ + maxZ) / 2) > 0.05) width = 2 * maxH;
            height = Math.max(maxY, maxY - minY - PAD);
        } else {
            width = 2 * maxH;
            height = maxY;
        }
        // the padding already reaches PAD below the entity: only what hangs further down is out of the box
        float below = Math.max(0, -minY - PAD);
        if (d.isInsideVehicle()) {
            notes.add("riding " + d.getVehicle().getType().name().toLowerCase(Locale.ROOT)
                    + ": sized around the model where it really is (translation, rotation, scale), never moved");
        }
        if (below > 0.01) {
            notes.add(String.format(Locale.ROOT, "%.2f blocks hang below the entity: the box can't reach under its feet"
                    + " (/tsentity look shift moves the entity down and the model up to fix it)", below));
        }
        float cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        if (d.getBillboard() == Display.Billboard.FIXED && Math.hypot(cx, cz) > 0.05) {
            notes.add(String.format(Locale.ROOT, "the model sits %.2f blocks off the entity's middle, so the square box"
                    + " is wider than the model (/tsentity look center moves the entity to the middle and the model back)",
                    Math.hypot(cx, cz)));
        }
        String detail = String.format(Locale.ROOT, "model %s%s, context %s; on screen x %.3f..%.3f, y %.3f..%.3f,"
                + " z %.3f..%.3f (blocks from the entity)", String.join(" + ", shape.models()),
                cmd == Models.Cmd.NONE ? "" : " (custom_model_data " + cmd.floats() + cmd.flags() + cmd.strings() + ")",
                context, minX, maxX, minY, maxY, minZ, maxZ);
        return new Fit(Math.max(MIN, width + 2 * margin - 2 * PAD), Math.max(MIN, height + margin - PAD), below, cx, cz, id,
                notes, detail);
    }

    /** The entity's own rotation and transformation, as the display renderer applies them (item displays turn 180°). */
    static Matrix4f entityMatrix(Display d) {
        Matrix4f m = new Matrix4f();
        if (d.getBillboard() == Display.Billboard.FIXED) {
            Location l = d.getLocation();
            m.rotate(new Quaternionf().rotationY(rad(-l.getYaw())).rotateX(rad(l.getPitch())));
        }
        Transformation t = d.getTransformation();
        m.translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale()).rotate(t.getRightRotation());
        return m.rotateY((float) Math.PI);
    }

    static float rad(float deg) { return (float) Math.toRadians(deg); }

    static float clamp(float v, float lim) { return Math.max(-lim, Math.min(lim, v)); }

    // commands ------------------------------------------------------------------------------------------------------

    /** `/tsentity <culling subcommand> ...`: a[0] is the subcommand. */
    public boolean onCommand(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            load(s);
            s.sendMessage(Component.text("Config reloaded; models are read again"
                    + (plugin.getConfig().getStringList("packs").stream().anyMatch(x -> x.startsWith("http"))
                            ? " (downloading the pack in the background)" : "") + ".", NamedTextColor.GREEN));
            return true;
        }
        if (sub.equals("feet")) {
            // /tsentity feet add|remove [model]|list: models that rest on tilted feet (seating/chair ones are automatic)
            List<String> list = new ArrayList<>(plugin.getConfig().getStringList("feet-models"));
            String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            if (what.equals("add") || (what.equals("remove") && a.length < 3)) {
                if (!(s instanceof Player p)) { s.sendMessage("Look at the display in game."); return true; }
                ItemDisplay d = looked(p);
                String k = modelKey(d != null ? d.getItemStack() : p.getInventory().getItemInMainHand());
                if (k == null) { p.sendMessage(Component.text("Look at a display (or hold its item) first.", NamedTextColor.RED)); return true; }
                if (what.equals("add")) { if (!list.contains(k)) list.add(k); }
                else if (!list.remove(k)) { p.sendMessage(Component.text("Not on the list: " + k, NamedTextColor.RED)); return true; }
                plugin.getConfig().set("feet-models", list);
                plugin.saveConfig();
                p.sendMessage(Component.text((what.equals("add") ? "Rests on its feet: " : "Removed: ") + k, NamedTextColor.GREEN));
            } else if (what.equals("remove")) {
                if (!list.remove(a[2])) { s.sendMessage(Component.text("Not on the list: " + a[2], NamedTextColor.RED)); return true; }
                plugin.getConfig().set("feet-models", list);
                plugin.saveConfig();
                s.sendMessage(Component.text("Removed: " + a[2], NamedTextColor.GREEN));
            } else {
                s.sendMessage(Component.text("Rest on tilted feet: anything named seating or chair"
                        + (list.isEmpty() ? "" : ", plus " + String.join(", ", list))
                        + ". /tsentity feet add (the display you look at) | remove [model]", NamedTextColor.YELLOW));
            }
            return true;
        }
        if (sub.equals("ignore")) {
            List<String> list = new ArrayList<>(plugin.getConfig().getStringList("ignore-offset"));
            String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
            if (what.equals("add")) {
                if (!(s instanceof Player p)) { s.sendMessage("Hold the item in game."); return true; }
                String k = modelKey(p.getInventory().getItemInMainHand());
                if (k == null) { p.sendMessage(Component.text("Hold the item whose model to ignore.", NamedTextColor.RED)); return true; }
                if (!list.contains(k)) list.add(k);
                plugin.getConfig().set("ignore-offset", list);
                plugin.saveConfig();
                p.sendMessage(Component.text("Ignored for auto-offset: " + k + " (its box is still fitted).", NamedTextColor.GREEN));
            } else if (what.equals("remove")) {
                String k = a.length > 2 ? a[2] : null;
                if (k == null || !list.remove(k)) {
                    s.sendMessage(Component.text("Not on the list: " + k + " (tab completes the names).", NamedTextColor.RED));
                    return true;
                }
                plugin.getConfig().set("ignore-offset", list);
                plugin.saveConfig();
                s.sendMessage(Component.text("Removed: " + k + ". Auto-offset applies to it again.", NamedTextColor.GREEN));
            } else {
                s.sendMessage(Component.text(list.isEmpty() ? "Nothing ignored. Hold an item and /tsentity ignore add."
                        : "Ignored for auto-offset: " + String.join(", ", list), NamedTextColor.YELLOW));
            }
            return true;
        }
        if (sub.equals("report")) {
            Report.run(this, s);
            return true;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage("Run it in game.");
            return true;
        }
        if (sub.equals("near") || sub.isEmpty()) {
            double r = 16;
            for (int i = 1; i < a.length; i++) if (a[i].matches("[0-9.]+")) r = Double.parseDouble(a[i]);
            boolean shift = flag(a, "shift", "shift-below"), center = flag(a, "center", "shift-sideways");
            int fitted = 0, unknown = 0;
            for (Entity e : p.getNearbyEntities(r, r, r)) {
                if (!(e instanceof ItemDisplay d)) continue;
                if (apply(d, shift, center) != null) fitted++; else unknown++;
            }
            p.sendMessage(Component.text("Fitted " + fitted + " item display(s) within " + (int) r + " blocks"
                    + (unknown > 0 ? "; " + unknown + " with no model in the packs" : "") + ".", NamedTextColor.GREEN));
            return true;
        }
        // `/tsentity center`, `/tsentity shift`, `/tsentity centre`: the same as `/tsentity look center` etc.
        if (sub.equals("center") || sub.equals("centre") || sub.equals("shift")) {
            String[] b = new String[a.length + 1];
            b[0] = "look";
            System.arraycopy(a, 0, b, 1, a.length);
            for (int i = 1; i < b.length; i++) if (b[i].equalsIgnoreCase("centre")) b[i] = "center";
            a = b;
            sub = "look";
        }
        if (sub.equals("look")) {
            ItemDisplay d = looked(p);
            if (d == null) {
                p.sendMessage(Component.text("Look at an item display within 8 blocks.", NamedTextColor.RED));
                return true;
            }
            float oldW = d.getDisplayWidth(), oldH = d.getDisplayHeight();
            Fit f = apply(d, flag(a, "shift", "shift-below"), flag(a, "center", "shift-sideways"));
            if (f == null) {
                p.sendMessage(Component.text("No model for its item in the packs.", NamedTextColor.RED));
                return true;
            }
            p.sendMessage(Component.text(String.format(Locale.ROOT, "%s: width %.2f, height %.2f (was %.2f x %.2f)",
                    f.model(), f.width(), f.height(), oldW, oldH), NamedTextColor.GREEN));
            p.sendMessage(Component.text(" " + f.detail(), NamedTextColor.GRAY));
            for (String n : f.notes()) p.sendMessage(Component.text(" • " + n, NamedTextColor.YELLOW));
            outline(p, d, f);
            return true;
        }
        s.sendMessage(Component.text("/tsentity near [radius] [shift] [center] | look [shift] [center] | center | shift | ignore <add|remove|list> | feet <add|remove|list> | report | reload",
                NamedTextColor.YELLOW));
        return true;
    }

    /** Completions for the culling subcommands (a[0] is the subcommand). */
    public List<String> onTabComplete(CommandSender s, String[] a) {
        List<String> options;
        if (a.length == 1) options = SUBCOMMANDS;
        else if (a.length == 2 && a[0].equalsIgnoreCase("ignore")) options = List.of("add", "remove", "list");
        else if (a.length == 2 && a[0].equalsIgnoreCase("feet")) options = List.of("add", "remove", "list");
        else if (a.length == 3 && a[0].equalsIgnoreCase("feet") && a[1].equalsIgnoreCase("remove")) {
            options = plugin.getConfig().getStringList("feet-models");
        }
        else if (a.length == 3 && a[0].equalsIgnoreCase("ignore") && a[1].equalsIgnoreCase("remove")) {
            options = plugin.getConfig().getStringList("ignore-offset");
        } else if (a.length >= 2 && (a[0].equalsIgnoreCase("look") || a[0].equalsIgnoreCase("near"))) {
            options = List.of("shift", "center");
        } else options = List.of();
        String typed = a[a.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    /** A word in the command, or its config switch. */
    boolean flag(String[] a, String word, String setting) {
        for (int i = 1; i < a.length; i++) if (a[i].equalsIgnoreCase(word)) return true;
        return plugin.getConfig().getBoolean(setting, false);
    }

    /** The item display nearest the player's line of sight. */
    ItemDisplay looked(Player p) {
        Vector eye = p.getEyeLocation().toVector(), dir = p.getEyeLocation().getDirection();
        ItemDisplay best = null;
        double bestOff = 1.5;
        for (Entity e : p.getNearbyEntities(8, 8, 8)) {
            if (!(e instanceof ItemDisplay d)) continue;
            Vector to = d.getLocation().toVector().add(new Vector(0, Math.max(0.2, d.getDisplayHeight() / 2), 0)).subtract(eye);
            double along = to.dot(dir);
            if (along <= 0) continue;
            double off = to.clone().subtract(dir.clone().multiply(along)).length();
            if (off < bestOff) { bestOff = off; best = d; }
        }
        return best;
    }

    /** Draw the culling box with particles for a few seconds, only for this player. */
    void outline(Player p, ItemDisplay d, Fit f) {
        Location base = d.getLocation();
        double hw = f.width() / 2 + PAD;    // draw the box the client really uses
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 0.6f);
        int[] runs = {0};
        boolean wasGlowing = d.isGlowing();
        d.setGlowing(true);                    // the item itself lights up while its box is drawn
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (runs[0]++ >= 10 || !p.isOnline()) {
                if (d.isValid()) d.setGlowing(wasGlowing);
                task.cancel();
                return;
            }
            double y0 = -PAD, y1 = f.height() + PAD;
            double[] xs = {-hw, hw}, ys = {y0, y1}, zs = {-hw, hw};
            for (double x : xs) for (double z : zs) line(p, base, x, y0, z, x, y1, z, dust);
            for (double y : ys) {
                line(p, base, -hw, y, -hw, hw, y, -hw, dust);
                line(p, base, -hw, y, hw, hw, y, hw, dust);
                line(p, base, -hw, y, -hw, -hw, y, hw, dust);
                line(p, base, hw, y, -hw, hw, y, hw, dust);
            }
        }, 0, 10);
    }

    static void line(Player p, Location base, double x0, double y0, double z0, double x1, double y1, double z1,
                     Particle.DustOptions dust) {
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int n = Math.max(2, (int) (len * 6));
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            p.spawnParticle(Particle.DUST, base.clone().add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t),
                    1, 0, 0, 0, 0, dust);
        }
    }
}
