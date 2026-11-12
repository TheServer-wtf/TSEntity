package com.mikemik44.tsentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /tsentity cullrange (2026-11-12, Nathan's request; designed with him on 2026-11-08): sets display view ranges
 * automatically by tracing how far each display can actually be seen from.
 * <p>
 * For every display in the radius, {@link #RAYS} rays go out from it in all directions and stop at the first opaque
 * block (a full, light-blocking cube: stone, planks, concrete...). The furthest any ray gets is the furthest spot
 * anyone could stand and still see the display; that distance plus {@link #MARGIN} blocks becomes its view range
 * (64 blocks = 1.0, Nathan's baseline: render distance 8, entity distance 100%). A prop in a closed room gets a
 * room-sized range; one by a window or an open doorway reaches out through it.
 * <p>
 * Nathan's rule: a display is only changed if its view range is 1 or below and higher than the traced one; ranges
 * are only ever lowered (one set lower by hand with the wand stays). Everything that isn't a full opaque block lets
 * the rays through: doors (so a prop doesn't vanish when someone opens one), trapdoors, fence gates, glass, panes,
 * leaves, slabs and stairs. That errs on the side of seeing too far, never too little. The block a display stands
 * in (often an invisible or barrier block) doesn't stop its rays either: they start counting walls after
 * {@link #SKIP} blocks.
 * <p>
 * Without "confirm" it only previews (what would change, by how much); with it, it applies and remembers the old
 * ranges for "/tsentity cullrange undo". The tracing runs off the main thread on chunk snapshots; unloaded chunks
 * count as open air.
 */
final class CullRange {
    static final int RAYS = 600;
    static final double MARGIN = 2.0;
    static final double MIN_BLOCKS = 4.0;
    static final double SKIP = 1.0;
    static final int MAX_RADIUS = 128;

    private final JavaPlugin plugin;
    private final Map<UUID, Float> undo = new HashMap<>();
    private boolean running;
    private static final double[][] DIRS = directions(RAYS);

    CullRange(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void command(Player p, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("undo")) {
            undo(p);
            return;
        }
        int radius = 32;
        boolean confirm = false;
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("confirm")) confirm = true;
            else {
                try {
                    radius = Integer.parseInt(a[i]);
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + "/tsentity cullrange [radius] [confirm]  |  /tsentity cullrange undo");
                    return;
                }
            }
        }
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));
        if (running) {
            p.sendMessage(ChatColor.RED + "A cullrange trace is already running; wait for it to finish.");
            return;
        }

        World w = p.getWorld();
        Location at = p.getLocation();
        List<Job> jobs = new ArrayList<>();
        int above = 0;
        for (Entity e : w.getNearbyEntities(at, radius, radius, radius)) {
            if (!(e instanceof Display d) || e.getLocation().distanceSquared(at) > (double) radius * radius) continue;
            float range = d.getViewRange();
            if (range > 1.0f) {   // Nathan's rule: only displays at 1 or below
                above++;
                continue;
            }
            Location l = d.getLocation();
            jobs.add(new Job(d.getUniqueId(), l.getX(), l.getY(), l.getZ(), range));
        }
        if (jobs.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "No displays at view range 1 or below within " + radius + " blocks"
                    + (above > 0 ? " (" + above + " above 1 left alone)." : "."));
            return;
        }

        // snapshot every loaded chunk the rays could reach (64 blocks past the displays at most)
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Job j : jobs) {
            minX = Math.min(minX, j.x);
            minZ = Math.min(minZ, j.z);
            maxX = Math.max(maxX, j.x);
            maxZ = Math.max(maxZ, j.z);
        }
        int cx0 = ((int) Math.floor(minX) - 64) >> 4, cz0 = ((int) Math.floor(minZ) - 64) >> 4;
        int cx1 = ((int) Math.floor(maxX) + 64) >> 4, cz1 = ((int) Math.floor(maxZ) + 64) >> 4;
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                if (w.isChunkLoaded(cx, cz)) chunks.put(key(cx, cz), w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
        boolean[] opaque = new boolean[Material.values().length];
        for (Material m : Material.values()) opaque[m.ordinal()] = m.isBlock() && m.isOccluding();
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();

        running = true;
        final int r = radius, aboveOne = above;
        final boolean apply = confirm;
        p.sendMessage(ChatColor.GRAY + "Tracing " + jobs.size() + " displays (" + RAYS + " rays each)...");
        long t0 = System.nanoTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Job j : jobs) j.traced = trace(j, chunks, opaque, minY, maxY);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Bukkit.getScheduler().runTask(plugin, () -> {
                running = false;
                finish(p, w, jobs, r, aboveOne, apply, ms);
            });
        });
    }

    /** One display to trace: where it is, its range now, and (after tracing) the range it needs. */
    private static final class Job {
        final UUID id;
        final double x, y, z;
        final float range;
        float traced;

        Job(UUID id, double x, double y, double z, float range) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.range = range;
        }
    }

    private void finish(Player p, World w, List<Job> jobs, int radius, int above, boolean apply, long ms) {
        if (!p.isOnline()) return;
        int lower = 0, kept = 0;
        double before = 0, after = 0;
        if (apply) undo.clear();
        for (Job j : jobs) {
            if (!(j.traced < j.range - 1e-4f)) {
                kept++;
                continue;
            }
            lower++;
            before += j.range;
            after += j.traced;
            if (!apply) continue;
            Entity e = Bukkit.getEntity(j.id);
            if (!(e instanceof Display d) || !d.isValid() || d.getViewRange() != j.range) continue;   // changed meanwhile
            undo.put(j.id, j.range);
            d.setViewRange(j.traced);
        }
        String avg = lower == 0 ? "" : String.format(Locale.ROOT, " (average %.2f -> %.2f, %.0f -> %.0f blocks)",
                before / lower, after / lower, before / lower * 64, after / lower * 64);
        p.sendMessage(ChatColor.GOLD + "Cull range" + ChatColor.GRAY + " (" + ms + " ms): " + ChatColor.YELLOW + jobs.size()
                + ChatColor.GRAY + " displays traced, " + ChatColor.GREEN + lower + (apply ? " lowered" : " would be lowered")
                + ChatColor.GRAY + avg + ", " + kept + " already as low or lower"
                + (above > 0 ? ", " + above + " above 1 left alone" : "") + ".");
        if (apply) {
            if (lower > 0) {
                TextComponent u = new TextComponent(ChatColor.GRAY + "[" + ChatColor.RED + "Undo" + ChatColor.GRAY + "]");
                u.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity cullrange undo"));
                u.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Put their old ranges back").create()));
                p.spigot().sendMessage(u);
            }
        } else if (lower > 0) {
            TextComponent a = new TextComponent(ChatColor.GRAY + "[" + ChatColor.GREEN + ChatColor.BOLD + "Apply" + ChatColor.GRAY + "]");
            a.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity cullrange " + radius + " confirm"));
            a.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Lower them (undo afterwards with /tsentity cullrange undo)").create()));
            p.spigot().sendMessage(a);
        }
    }

    private void undo(Player p) {
        if (undo.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Nothing to undo.");
            return;
        }
        int n = 0;
        for (Map.Entry<UUID, Float> e : undo.entrySet()) {
            Entity en = Bukkit.getEntity(e.getKey());
            if (en instanceof Display d && d.isValid()) {
                d.setViewRange(e.getValue());
                n++;
            }
        }
        undo.clear();
        p.sendMessage(ChatColor.GREEN + "Put back the view range of " + n + " displays.");
    }

    // ---- tracing (off the main thread: snapshots only) ----

    /** The view range a display needs: how far its furthest ray gets, plus the margin, never above what it has. */
    static float trace(Job j, Map<Long, ChunkSnapshot> chunks, boolean[] opaque, int minY, int maxY) {
        double max = j.range * 64.0;
        double far = 0;
        for (double[] d : DIRS) {
            far = Math.max(far, ray(j.x, j.y, j.z, d, max, chunks, opaque, minY, maxY));
            if (far >= max) return j.range;   // already reaches its whole range: nothing to lower
        }
        double blocks = Math.max(MIN_BLOCKS, far + MARGIN);
        return (float) Math.min(j.range, blocks / 64.0);
    }

    /** How far a ray from (x, y, z) along d gets before an opaque block (or max). Voxel stepping (Amanatides-Woo). */
    static double ray(double x, double y, double z, double[] d, double max, Map<Long, ChunkSnapshot> chunks,
                      boolean[] opaque, int minY, int maxY) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
        double tdx = d[0] == 0 ? Double.MAX_VALUE : Math.abs(1 / d[0]);
        double tdy = d[1] == 0 ? Double.MAX_VALUE : Math.abs(1 / d[1]);
        double tdz = d[2] == 0 ? Double.MAX_VALUE : Math.abs(1 / d[2]);
        double tmx = d[0] == 0 ? Double.MAX_VALUE : ((sx > 0 ? bx + 1 - x : x - bx) * tdx);
        double tmy = d[1] == 0 ? Double.MAX_VALUE : ((sy > 0 ? by + 1 - y : y - by) * tdy);
        double tmz = d[2] == 0 ? Double.MAX_VALUE : ((sz > 0 ? bz + 1 - z : z - bz) * tdz);
        double t = 0;
        while (t < max) {
            // into the next block
            if (tmx < tmy && tmx < tmz) {
                t = tmx;
                tmx += tdx;
                bx += sx;
            } else if (tmy < tmz) {
                t = tmy;
                tmy += tdy;
                by += sy;
            } else {
                t = tmz;
                tmz += tdz;
                bz += sz;
            }
            if (t >= max) return max;
            if (by < minY) return t;          // under the world: as good as a floor
            if (by >= maxY) return max;       // out of the top: open sky
            if (t < SKIP) continue;            // the display's own block(s)
            ChunkSnapshot c = chunks.get(key(bx >> 4, bz >> 4));
            if (c == null) continue;           // not loaded: open air
            if (opaque[c.getBlockType(bx & 15, by, bz & 15).ordinal()]) return t;
        }
        return max;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** n directions spread evenly over the sphere (Fibonacci lattice). */
    static double[][] directions(int n) {
        double[][] out = new double[n][];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n, r = Math.sqrt(1 - y * y), th = golden * i;
            out[i] = new double[]{Math.cos(th) * r, y, Math.sin(th) * r};
        }
        return out;
    }
}
