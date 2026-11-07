package com.mikemik44.tsentity;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Old models into their remade variants (Nathan). A remake is often on another item, scaled, turned and offset
 * differently, so nothing in the files says how to swap one for the other; one correctly placed pair does. The pair
 * gives the exact change between the two placements (old placement inverted, times new placement); applying that
 * change to any other old copy puts the new model where the old one was. Pairs are kept in variants.yml by the old
 * model's name; converted displays remember what they were, so a conversion can be undone.
 */
final class Variants {

    private final JavaPlugin plugin;
    private final Function<ItemStack, String> modelKey;
    /** Refit a display's culling box without moving it (a conversion places the model exactly; no re-centring). */
    private final java.util.function.Consumer<ItemDisplay> refit;
    /** The item display's 180° turn plus the model's own transform for a mode (from the culling part). */
    private final java.util.function.BiFunction<ItemStack, ItemDisplay.ItemDisplayTransform, Matrix4f> itemMatrix;
    private final NamespacedKey wasKey;
    private final File file;
    private final YamlConfiguration data;
    /** Per player: the old half of a pair being taught. */
    private final Map<UUID, UUID> pendingOld = new HashMap<>();

    Variants(JavaPlugin plugin, Function<ItemStack, String> modelKey, java.util.function.Consumer<ItemDisplay> refit,
            java.util.function.BiFunction<ItemStack, ItemDisplay.ItemDisplayTransform, Matrix4f> itemMatrix) {
        this.plugin = plugin;
        this.itemMatrix = itemMatrix;
        this.modelKey = modelKey;
        this.refit = refit;
        this.wasKey = new NamespacedKey(plugin, "variant_was");
        this.file = new File(plugin.getDataFolder(), "variants.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    List<String> names() {
        ConfigurationSection s = data.getConfigurationSection("variants");
        List<String> out = new ArrayList<>();
        if (s != null) for (String k : s.getKeys(false)) out.add(s.getString(k + ".old"));
        return out;
    }

    /** The whole placement as one matrix: position, the entity's turn, its transformation. */
    static Matrix4f placement(Display d) {
        Location l = d.getLocation();
        Matrix4f m = new Matrix4f().translate((float) l.getX(), (float) l.getY(), (float) l.getZ());
        if (d.getBillboard() == Display.Billboard.FIXED) {
            m.rotateY((float) Math.toRadians(-l.getYaw())).rotateX((float) Math.toRadians(l.getPitch()));
        }
        return m.mul(transformation(d));
    }

    /**
     * Where the model's centred geometry is drawn: the placement, the item display's turn and the model's transform
     * for the display's mode. Comparing these (not placements) makes a pair work across modes, e.g. an old model on
     * fixed and its remake on none (Nathan).
     */
    Matrix4f drawn(ItemDisplay d) {
        return placement(d).mul(itemMatrix.apply(d.getItemStack(), d.getItemDisplayTransform()));
    }

    static Matrix4f transformation(Display d) {
        Transformation t = d.getTransformation();
        return new Matrix4f().translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale())
                .rotate(t.getRightRotation());
    }

    /** `/tsentity variant <old|new|convert|undo|list|remove>`; `selected` is the wand's selection. */
    @SuppressWarnings("deprecation")
    void command(Player p, String[] a, Entity selected) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        switch (sub) {
            case "old" -> {
                if (!(selected instanceof ItemDisplay d)) { p.sendMessage(ChatColor.RED + "Select the OLD item display with the wand first."); return; }
                pendingOld.put(p.getUniqueId(), d.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Old: " + ChatColor.YELLOW + modelKey.apply(d.getItemStack())
                        + ChatColor.GREEN + ". Now select the NEW one, placed exactly over it, and /tsentity variant new.");
            }
            case "new" -> {
                UUID oldId = pendingOld.get(p.getUniqueId());
                Entity oldE = oldId == null ? null : plugin.getServer().getEntity(oldId);
                if (!(oldE instanceof ItemDisplay oldD)) { p.sendMessage(ChatColor.RED + "Pick the old one first: /tsentity variant old."); return; }
                if (!(selected instanceof ItemDisplay newD) || newD == oldD) { p.sendMessage(ChatColor.RED + "Select the NEW item display with the wand."); return; }
                Matrix4f change = drawn(oldD).invert().mul(drawn(newD));
                String oldName = modelKey.apply(oldD.getItemStack()), newName = modelKey.apply(newD.getItemStack());
                String key = "variants." + Integer.toHexString(oldName.hashCode());
                data.set(key + ".old", oldName);
                data.set(key + ".new", newName);
                data.set(key + ".item", Base64.getEncoder().encodeToString(newD.getItemStack().serializeAsBytes()));
                float[] f = new float[16];
                change.get(f);
                List<Float> list = new ArrayList<>();
                for (float v : f) list.add(v);
                data.set(key + ".change", list);
                // where the remake's entity stands, in the old model's own frame, and how much more it's turned:
                // each conversion moves its entity to the same spot relative to that copy (Nathan's ask)
                data.set(key + ".mode", newD.getItemDisplayTransform().name());
                data.set(key + ".v", 2);
                org.joml.Vector3f origin = drawn(oldD).invert().transformPosition(new org.joml.Vector3f(
                        (float) newD.getLocation().getX(), (float) newD.getLocation().getY(),
                        (float) newD.getLocation().getZ()));
                data.set(key + ".origin", List.of(origin.x, origin.y, origin.z));
                data.set(key + ".yaw", newD.getLocation().getYaw() - oldD.getLocation().getYaw());
                data.set(key + ".pitch", newD.getLocation().getPitch() - oldD.getLocation().getPitch());
                save();
                pendingOld.remove(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Learned: " + ChatColor.YELLOW + oldName + ChatColor.GREEN + " -> "
                        + ChatColor.YELLOW + newName + ChatColor.GREEN + ". /tsentity variant convert [radius] swaps the old ones.");
            }
            case "convert" -> {
                boolean confirm = false;
                double r = 16;
                for (int i = 2; i < a.length; i++) {
                    if (a[i].equalsIgnoreCase("confirm")) confirm = true;
                    else if (a[i].matches("[0-9.]+")) r = Double.parseDouble(a[i]);
                }
                List<ItemDisplay> found = new ArrayList<>();
                for (Entity e : p.getNearbyEntities(r, r, r)) {
                    if (e instanceof ItemDisplay d && section(modelKey.apply(d.getItemStack())) != null) found.add(d);
                }
                if (!confirm) {
                    p.sendMessage(ChatColor.GOLD + "" + found.size() + " old display(s) with a learned variant within " + (int) r + " blocks.");
                    if (found.isEmpty()) return;
                    TextComponent go = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "[Convert them]");
                    go.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity variant convert " + (int) r + " confirm"));
                    p.spigot().sendMessage(go);
                    return;
                }
                int done = 0;
                for (ItemDisplay d : found) if (convert(d)) done++;
                p.sendMessage(ChatColor.GREEN + "Converted " + done + " display(s) to their new variant."
                        + ChatColor.GRAY + " /tsentity variant undo " + (int) r + " reverts them.");
            }
            case "undo" -> {
                double r = a.length > 2 && a[2].matches("[0-9.]+") ? Double.parseDouble(a[2]) : 16;
                int back = 0;
                for (Entity e : p.getNearbyEntities(r, r, r)) {
                    if (e instanceof ItemDisplay d && undo(d)) back++;
                }
                p.sendMessage(ChatColor.GREEN + "Reverted " + back + " display(s) to their old model.");
            }
            case "remove" -> {
                String name = a.length > 2 ? a[2] : "";
                String k = keyOf(name);
                if (k == null) { p.sendMessage(ChatColor.RED + "No variant learned for " + name + " (tab completes)."); return; }
                data.set("variants." + k, null);
                save();
                p.sendMessage(ChatColor.GREEN + "Forgot the variant for " + name + ".");
            }
            case "list" -> {
                ConfigurationSection s = data.getConfigurationSection("variants");
                if (s == null || s.getKeys(false).isEmpty()) { p.sendMessage(ChatColor.YELLOW + "No variants learned yet."); return; }
                for (String k : s.getKeys(false)) {
                    p.sendMessage(ChatColor.YELLOW + s.getString(k + ".old") + ChatColor.GRAY + " -> " + ChatColor.GREEN + s.getString(k + ".new"));
                }
            }
            default -> {
                p.sendMessage(ChatColor.YELLOW + "/tsentity variant old " + ChatColor.GRAY + "- the wand's selection is the old model");
                p.sendMessage(ChatColor.YELLOW + "/tsentity variant new " + ChatColor.GRAY + "- the wand's selection is its remake, placed over it");
                p.sendMessage(ChatColor.YELLOW + "/tsentity variant convert [radius] [confirm]" + ChatColor.GRAY + " - swap old ones for the remake");
                p.sendMessage(ChatColor.YELLOW + "/tsentity variant undo [radius] | list | remove <model>");
            }
        }
    }

    private ConfigurationSection section(String oldName) {
        String k = keyOf(oldName);
        return k == null ? null : data.getConfigurationSection("variants." + k);
    }

    private String keyOf(String oldName) {
        ConfigurationSection s = data.getConfigurationSection("variants");
        if (s == null || oldName == null) return null;
        for (String k : s.getKeys(false)) if (oldName.equals(s.getString(k + ".old"))) return k;
        return null;
    }

    /** True if a pair is taught for this display's model. */
    boolean hasPair(ItemDisplay d) {
        return section(modelKey.apply(d.getItemStack())) != null;
    }

    /** Swap one old display for the remake: same entity, new item, transformation times the learned change. */
    boolean convert(ItemDisplay d) {
        ConfigurationSection s = section(modelKey.apply(d.getItemStack()));
        if (s == null) return false;
        List<Float> list = s.getFloatList("change");
        if (list.size() != 16) return false;
        float[] f = new float[16];
        for (int i = 0; i < 16; i++) f[i] = list.get(i);
        Matrix4f change = new Matrix4f().set(f);
        ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item")));
        // what it was, for undo: the old item and transformation
        float[] was = new float[16];
        transformation(d).get(was);
        StringBuilder rec = new StringBuilder(Base64.getEncoder().encodeToString(d.getItemStack().serializeAsBytes()));
        for (float v : was) rec.append(';').append(v);
        Location from = d.getLocation();
        rec.append(';').append(from.getX()).append(';').append(from.getY()).append(';').append(from.getZ())
                .append(';').append(from.getYaw()).append(';').append(from.getPitch())
                .append(';').append(d.getItemDisplayTransform().name());
        d.getPersistentDataContainer().set(wasKey, PersistentDataType.STRING, rec.toString());
        boolean v2 = s.getInt("v", 1) >= 2;
        ItemDisplay.ItemDisplayTransform mode = d.getItemDisplayTransform();
        if (v2 && s.isString("mode")) mode = ItemDisplay.ItemDisplayTransform.valueOf(s.getString("mode"));
        // where the remake has to end up, all of it: this copy's drawn frame times the learned change, then back out
        // through the remake's own turn and mode transform to the placement that draws it there
        Matrix4f target = v2
                ? drawn(d).mul(change).mul(itemMatrix.apply(item, mode).invert())
                : placement(d).mul(change);
        List<Float> o = s.getFloatList("origin");
        if (o.size() == 3) {
            // move the entity where the remake's entity stands relative to this copy, turned the same extra amount,
            // then the transformation is what's left between that spot and the target
            org.joml.Vector3f at = (v2 ? drawn(d) : placement(d))
                    .transformPosition(new org.joml.Vector3f(o.get(0), o.get(1), o.get(2)));
            Location l = d.getLocation();
            l.set(at.x, at.y, at.z);
            l.setYaw(l.getYaw() + (float) s.getDouble("yaw"));
            l.setPitch(l.getPitch() + (float) s.getDouble("pitch"));
            int dur = d.getTeleportDuration();
            d.setTeleportDuration(0);
            d.teleport(l);
            d.setTeleportDuration(dur);
            Matrix4f spot = new Matrix4f().translate(at.x, at.y, at.z);
            if (d.getBillboard() == Display.Billboard.FIXED) {
                spot.rotateY((float) Math.toRadians(-l.getYaw())).rotateX((float) Math.toRadians(l.getPitch()));
            }
            d.setTransformationMatrix(spot.invert().mul(target));
        } else {
            // a pair taught before positions were recorded: keep the entity where it is
            d.setTransformationMatrix(transformation(d).mul(change));
        }
        d.setItemDisplayTransform(mode);
        d.setItemStack(item);
        refit.accept(d);
        return true;
    }

    boolean undo(ItemDisplay d) {
        String rec = d.getPersistentDataContainer().get(wasKey, PersistentDataType.STRING);
        if (rec == null) return false;
        String[] v = rec.split(";");
        if (v.length != 17 && v.length != 22 && v.length != 23) return false;
        if (v.length == 23) d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf(v[22]));
        float[] f = new float[16];
        for (int i = 0; i < 16; i++) f[i] = Float.parseFloat(v[i + 1]);
        if (v.length >= 22) {
            Location back = new Location(d.getWorld(), Double.parseDouble(v[17]), Double.parseDouble(v[18]),
                    Double.parseDouble(v[19]), Float.parseFloat(v[20]), Float.parseFloat(v[21]));
            int dur = d.getTeleportDuration();
            d.setTeleportDuration(0);
            d.teleport(back);
            d.setTeleportDuration(dur);
        }
        d.setItemStack(ItemStack.deserializeBytes(Base64.getDecoder().decode(v[0])));
        d.setTransformationMatrix(new Matrix4f().set(f));
        d.getPersistentDataContainer().remove(wasKey);
        refit.accept(d);
        return true;
    }

    private void save() {
        try {
            data.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("couldn't save variants.yml: " + e.getMessage());
        }
    }
}
