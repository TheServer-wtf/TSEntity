package com.mikemik44.tsentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mikemik44.tsentity.culling.Culling;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Turns item frames holding a prop into item displays that look the same, for replacing old item-frame builds.
 * The display reproduces the client's item frame renderer exactly: from the frame's spot it steps 0.46875 along the
 * facing (the block's centre), turns by the frame's pitch and 180 - yaw, moves 0.4375 toward the wall (0.5 for an
 * invisible frame), turns the item by its 45° steps, scales by 0.5 and draws the item in its FIXED context, which is
 * the display's mode too. Each display remembers its frame, so /tsentity frames undo puts the frames back.
 */
final class Frames {

    private final JavaPlugin plugin;
    private final NamespacedKey fromFrame;
    private final Variants variants;
    private final Culling culling;

    Frames(JavaPlugin plugin, Variants variants, Culling culling) {
        this.plugin = plugin;
        this.variants = variants;
        this.culling = culling;
        this.fromFrame = new NamespacedKey(plugin, "from_frame");
    }

    /** `/tsentity frames [radius] [confirm]` and `/tsentity frames undo [radius]`. */
    @SuppressWarnings("deprecation")
    void command(Player p, String[] a) {
        boolean undo = a.length > 1 && a[1].equalsIgnoreCase("undo");
        boolean confirm = false;
        double r = 16;
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("confirm")) confirm = true;
            else if (a[i].matches("[0-9.]+")) r = Double.parseDouble(a[i]);
        }
        if (undo) {
            int back = 0;
            for (Entity e : p.getNearbyEntities(r, r, r)) {
                if (e instanceof ItemDisplay d && d.getPersistentDataContainer().has(fromFrame)) {
                    variants.undo(d);                // a remake applied on conversion goes first: the frame's own item
                    if (restore(d)) back++;
                }
            }
            p.sendMessage(ChatColor.GREEN + "Put back " + back + " item frame(s) within " + (int) r + " blocks.");
            return;
        }
        List<ItemFrame> frames = new ArrayList<>();
        int maps = 0;
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof ItemFrame f) || f.getItem().getType().isAir()) continue;
            if (f.getItem().getType() == Material.FILLED_MAP) { maps++; continue; }   // maps draw differently: left alone
            frames.add(f);
        }
        if (!confirm) {
            p.sendMessage(ChatColor.GOLD + "" + frames.size() + " item frame(s) with an item within " + (int) r
                    + " blocks" + (maps > 0 ? ChatColor.GRAY + " (" + maps + " map(s) left alone)" : "") + ".");
            if (frames.isEmpty()) return;
            TextComponent go = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "[Convert them]");
            go.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/tsentity frames " + (int) r + " confirm"));
            p.spigot().sendMessage(go);
            p.sendMessage(ChatColor.GRAY + "Each becomes an item display that looks the same; the frame is removed."
                    + " /tsentity frames undo " + (int) r + " puts them back.");
            return;
        }
        int made = 0, remade = 0, tidied = 0;
        boolean tidy = plugin.getConfig().getBoolean("frames-tidy", true);
        for (ItemFrame f : frames) {
            BlockFace face = f.getFacing();
            ItemDisplay d = convert(f);
            if (d == null) continue;
            made++;
            // a taught remake for this model (maybe on another mode, better centred) replaces it straight away
            if (variants.hasPair(d) && variants.convert(d)) remade++;
            else if (tidy && tidy(d, face)) tidied++;
        }
        p.sendMessage(ChatColor.GREEN + "Converted " + made + " item frame(s) into item displays"
                + (remade > 0 ? ", " + remade + " of them straight to their taught remake" : "")
                + (tidied > 0 ? ", " + tidied + " tidied (mode none, straightened, flush)" : "") + ".");
    }

    /** The display for one frame (the frame is removed). */
    ItemDisplay convert(ItemFrame f) {
        BlockFace face = f.getFacing();
        Location at = f.getLocation().add(face.getModX() * 0.46875, face.getModY() * 0.46875, face.getModZ() * 0.46875);
        at.setYaw(0);
        at.setPitch(0);
        float pitch = f.getLocation().getPitch(), yaw = f.getLocation().getYaw();
        boolean invisible = !f.isVisible();
        int steps = f.getRotation().ordinal();
        ItemStack item = f.getItem().clone();
        boolean glow = f instanceof GlowItemFrame;
        String record = String.join(";", face.name(), f.getRotation().name(), String.valueOf(f.isVisible()),
                String.valueOf(f.isFixed()), String.valueOf(glow), String.valueOf(f.isInvulnerable()),
                fmt(f.getLocation().getX()), fmt(f.getLocation().getY()), fmt(f.getLocation().getZ()));
        Matrix4f m = new Matrix4f()
                .rotateX(rad(pitch))
                .rotateY(rad(180 - yaw))
                .translate(0, 0, invisible ? 0.5f : 0.4375f)
                .rotateZ(rad(steps * 45f))
                .scale(0.5f)
                .rotateY((float) -Math.PI);          // the item display renderer turns its item 180°: undo it
        ItemDisplay d = f.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setItemStack(item);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setTransformationMatrix(m);
            if (glow) x.setBrightness(new Display.Brightness(15, 15));   // glow frames draw their item full bright
            x.getPersistentDataContainer().set(fromFrame, PersistentDataType.STRING, record);
        });
        f.setItem(null, false);
        f.remove();
        return d;
    }

    /** How far off a 22.5° step a turn may be and still count as a mistake to straighten. */
    private static final float STRAIGHTEN_DEG = 3f;
    /** How close the model's wall side has to be to a block line to be pulled onto it. */
    private static final float FLUSH = 0.1f;

    /**
     * Nathan's hand-placed remakes (2026-11-06): the frame look copied exactly also copies its small mistakes. Tidy
     * the display the way he places them: mode none; a turn a few degrees off a 22.5° step straightened (e.g. a model's
     * item-frame transform leaning 1.25°); the model's side toward the wall the frame hung on pulled onto the nearest
     * block line when it's within 0.1 block (sunk 0.07 into a floor, 1/4 px into a wall); the entity in the middle of
     * the block the model sits in, unturned, with all of the placement in its transformation. False (left as the frame
     * drew it) when the packs don't know the model.
     */
    boolean tidy(ItemDisplay d, BlockFace face) {
        java.util.List<Vector3f> corners = culling.corners(d.getItemStack());
        if (corners == null) return false;
        // where the model's centred geometry is drawn now (placement, the display's turn, the fixed transform)
        Matrix4f drawn = variants.drawn(d);
        Vector3f pos = drawn.getTranslation(new Vector3f());
        Vector3f scale = drawn.getScale(new Vector3f());
        Quaternionf rot = drawn.getNormalizedRotation(new Quaternionf());
        Vector3f euler = rot.getEulerAnglesYXZ(new Vector3f());
        Quaternionf straight = new Quaternionf().rotationYXZ(snap(euler.y), snap(euler.x), snap(euler.z));
        Matrix4f clean = new Matrix4f().translation(pos).rotate(straight).scale(scale);
        // the model's box in the world as drawn by `clean`
        Matrix4f toWorld = new Matrix4f(clean).translate(-0.5f, -0.5f, -0.5f).scale(1 / 16f);
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), v = new Vector3f();
        for (Vector3f c : corners) {
            toWorld.transformPosition(c, v);
            min.min(v);
            max.max(v);
        }
        // flush: the side facing the wall (the frame faces away from it) onto the nearest block line
        int axis = face.getModX() != 0 ? 0 : face.getModY() != 0 ? 1 : 2;
        int sign = face.getModX() + face.getModY() + face.getModZ();
        float side = sign > 0 ? min.get(axis) : max.get(axis);
        float line = Math.round(side);
        // tilted feet (seating/chairs, /tsentity feet): each cube counts from its 4th corner toward the wall (the highest
        // bottom corner of a leaning leg; a straight cube's own side), so the legs sink in until their feet are flat on it
        float rest = side;
        if (culling.feet(d.getItemStack())) {
            rest = sign > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            float[] along = new float[8];
            for (int i = 0; i + 8 <= corners.size(); i += 8) {
                for (int j = 0; j < 8; j++) along[j] = toWorld.transformPosition(corners.get(i + j), v).get(axis);
                java.util.Arrays.sort(along);
                rest = sign > 0 ? Math.min(rest, along[3]) : Math.max(rest, along[4]);
            }
        }
        float gap = line - rest;
        if (Math.abs(line - side) <= FLUSH && Math.abs(gap) > 1e-4f) {
            Vector3f shift = new Vector3f().setComponent(axis, gap);
            clean.translateLocal(shift);
            min.add(shift);
            max.add(shift);
        }
        // the entity: middle of the block the model sits in, no turn; mode none (no model transform in between)
        Vector3f mid = new Vector3f(min).add(max).mul(0.5f);
        Location spot = new Location(d.getWorld(), Math.floor(mid.x) + 0.5, Math.floor(mid.y) + 0.5,
                Math.floor(mid.z) + 0.5, 0, 0);
        // placement · (item display's 180° turn) = clean, so placement = clean · turn back
        Matrix4f placement = new Matrix4f(clean).rotateY((float) -Math.PI);
        Matrix4f transformation = new Matrix4f().translation((float) -spot.getX(), (float) -spot.getY(),
                (float) -spot.getZ()).mul(placement);
        Culling.placedExactly.add(d.getUniqueId());
        int dur = d.getTeleportDuration();
        d.setTeleportDuration(0);
        d.teleport(spot);
        d.setTeleportDuration(dur);
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setTransformationMatrix(transformation);
        return true;
    }

    /** An angle a few degrees off a 22.5° step put on the step; any other angle kept. */
    private static float snap(float radians) {
        float step = (float) Math.toRadians(22.5);
        float near = Math.round(radians / step) * step;
        return Math.abs(radians - near) <= Math.toRadians(STRAIGHTEN_DEG) ? near : radians;
    }

    /** Put a converted display back as the frame it came from. */
    boolean restore(ItemDisplay d) {
        String rec = d.getPersistentDataContainer().get(fromFrame, PersistentDataType.STRING);
        if (rec == null) return false;
        String[] v = rec.split(";");
        try {
            BlockFace face = BlockFace.valueOf(v[0]);
            Rotation rot = Rotation.valueOf(v[1]);
            boolean visible = Boolean.parseBoolean(v[2]), fixed = Boolean.parseBoolean(v[3]);
            boolean glow = Boolean.parseBoolean(v[4]), invulnerable = Boolean.parseBoolean(v[5]);
            Location at = new Location(d.getWorld(), Double.parseDouble(v[6]), Double.parseDouble(v[7]),
                    Double.parseDouble(v[8]));
            ItemStack item = d.getItemStack();
            Class<? extends ItemFrame> type = glow ? GlowItemFrame.class : ItemFrame.class;
            d.getWorld().spawn(at, type, f -> {
                f.setFacingDirection(face, true);
                f.setItem(item, false);
                f.setRotation(rot);
                f.setVisible(visible);
                f.setFixed(fixed);
                f.setInvulnerable(invulnerable);
            });
            d.remove();
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("couldn't put back the frame of " + d.getUniqueId() + ": " + e.getMessage());
            return false;
        }
    }

    private static float rad(float deg) {
        return (float) Math.toRadians(deg);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }
}
