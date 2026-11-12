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
 * For every display in the radius, rays go out in all directions and stop at the first opaque block (a full,
 * light-blocking cube: stone, planks, concrete...). They start from points spread through the model itself, not just
 * its origin (2026-11-12, Nathan: a model more than a block wide round a corner): the box its model's corners fill
 * (Culling.modelBox: rotation, transformation and display transform included), sampled on a grid up to 5 per side
 * (every ~0.75 blocks), {@link #RAYS} rays shared out among the points. The client hides a display by the distance from
 * the viewer to the entity's origin, so each spot a ray reaches counts by its distance to the origin; the furthest
 * such spot, plus {@link #MARGIN} blocks, becomes its view range (64 blocks = 1.0, Nathan's baseline: render distance
 * 8, entity distance 100%). A prop in a closed room gets a room-sized range; one by a window or an open doorway, or with
 * a part reaching round a corner, reaches out through it. Sample points inside an opaque block (a model end pushed into
 * a wall) are hidden there anyway and are skipped.
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
    static final int RAYS = 2400;          // per display, shared out among its sample points
    static final int MIN_RAYS = 96;        // per sample point at least
    static final double MARGIN = 2.0;
    static final double MIN_BLOCKS = 4.0;
    static final double SKIP = 1.0;
    static final int MAX_RADIUS = 128;

    private final JavaPlugin plugin;
    private final com.mikemik44.tsentity.culling.Culling culling;
    private final Map<UUID, Float> undo = new HashMap<>();
    private boolean running;
    private static final Map<Integer, double[][]> DIRS = new java.util.concurrent.ConcurrentHashMap<>();

    CullRange(JavaPlugin plugin, com.mikemik44.tsentity.culling.Culling culling) {
        this.plugin = plugin;
        this.culling = culling;
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
            jobs.add(new Job(d.getUniqueId(), l.getX(), l.getY(), l.getZ(), range, culling.modelBox(d)));
        }
        if (jobs.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "No displays at view range 1 or below within " + radius + " blocks"
                    + (above > 0 ? " (" + above + " above 1 left alone)." : "."));
            return;
        }

        // snapshot every loaded chunk the rays could reach (64 blocks past the displays, plus their models' reach)
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Job j : jobs) {
            minX = Math.min(minX, j.x);
            minZ = Math.min(minZ, j.z);
            maxX = Math.max(maxX, j.x);
            maxZ = Math.max(maxZ, j.z);
        }
        int reach = 64 + 8;
        int cx0 = ((int) Math.floor(minX) - reach) >> 4, cz0 = ((int) Math.floor(minZ) - reach) >> 4;
        int cx1 = ((int) Math.floor(maxX) + reach) >> 4, cz1 = ((int) Math.floor(maxZ) + reach) >> 4;
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
        p.sendMessage(ChatColor.GRAY + "Tracing " + jobs.size() + " displays (from their whole models, " + RAYS + " rays each)...");
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
    static final class Job {
        final UUID id;
        final double x, y, z;
        final float range;
        final float[] box;   // the model's box around the entity (blocks), or null: the origin only
        float traced;

        Job(UUID id, double x, double y, double z, float range, float[] box) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.range = range;
            this.box = box;
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

    /**
     * The view range a display needs: rays from points through its model (or its origin); each spot a ray reaches
     * counts by its distance to the entity's origin (what the client measures); the furthest, plus the margin, never
     * above what it has.
     */
    static float trace(Job j, Map<Long, ChunkSnapshot> chunks, boolean[] opaque, int minY, int maxY) {
        double max = j.range * 64.0;
        List<double[]> points = samples(j.box);
        // points inside an opaque block are hidden in it: skip them (all of them inside: trace from the origin anyway)
        List<double[]> open = new ArrayList<>();
        for (double[] pt : points) if (!opaqueAt(j.x + pt[0], j.y + pt[1], j.z + pt[2], chunks, opaque, minY, maxY)) open.add(pt);
        if (open.isEmpty()) open.add(new double[]{0, 0, 0});
        double[][] dirs = DIRS.computeIfAbsent(Math.max(MIN_RAYS, RAYS / open.size()), CullRange::directions);
        double far = 0;
        for (double[] pt : open) {
            boolean origin = pt[0] == 0 && pt[1] == 0 && pt[2] == 0;
            double off = Math.sqrt(pt[0] * pt[0] + pt[1] * pt[1] + pt[2] * pt[2]);
            for (double[] d : dirs) {
                double t = ray(j.x + pt[0], j.y + pt[1], j.z + pt[2], d, max + off, chunks, opaque, minY, maxY, origin ? SKIP : 0.3);
                // the spot it reached, measured from the origin
                double qx = pt[0] + d[0] * t, qy = pt[1] + d[1] * t, qz = pt[2] + d[2] * t;
                far = Math.max(far, Math.sqrt(qx * qx + qy * qy + qz * qz));
                if (far >= max) return j.range;   // already reaches its whole range: nothing to lower
            }
        }
        // enclosed: rays can slip past a narrow opening (a long corridor needs one within a degree or two), so check
        // the open space itself: every cell reachable from the model, furthest first, until one can see it
        far = Math.max(far, refine(j, open, far, max, chunks, opaque, minY, maxY));
        if (far >= max) return j.range;
        double blocks = Math.max(MIN_BLOCKS, far + MARGIN);
        return (float) Math.min(j.range, blocks / 64.0);
    }

    static final int MAX_CELLS = 300_000, MAX_CHECKS = 150_000;
    private static final double HALF_DIAG = Math.sqrt(3) / 2;   // a cell's centre to its far corner

    /**
     * The second pass: flood the open space (anything that isn't an opaque block) from the model's sample points, out to
     * its range; then, furthest first, the first cell from whose middle any sample point is in plain sight decides
     * (its far corner's distance to the origin). Gives up keeping the range (returns max) when the space is too big to
     * flood (open sky, a huge hall) or the sky is reached; when the sight checks run out, the cell being checked counts
     * as seen (never too short).
     */
    static double refine(Job j, List<double[]> pts, double far, double max, Map<Long, ChunkSnapshot> chunks, boolean[] opaque,
                         int minY, int maxY) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        List<double[]> cands = new ArrayList<>();   // {x, y, z, distance of its far corner}
        for (double[] p : pts) {
            int x = (int) Math.floor(j.x + p[0]), y = (int) Math.floor(j.y + p[1]), z = (int) Math.floor(j.z + p[2]);
            if (seen.add(cell(x, y, z))) queue.add(new int[]{x, y, z});
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] s : steps) {
                int x = c[0] + s[0], y = c[1] + s[1], z = c[2] + s[2];
                if (y >= maxY) return max;   // open to the sky: leave it
                if (y < minY) continue;
                double dx = x + 0.5 - j.x, dy = y + 0.5 - j.y, dz = z + 0.5 - j.z;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz) + HALF_DIAG;
                if (d - 2 * HALF_DIAG > max) continue;   // beyond its range
                if (!seen.add(cell(x, y, z))) continue;
                if (seen.size() > MAX_CELLS) return max;
                if (opaqueAt(x + 0.5, y + 0.5, z + 0.5, chunks, opaque, minY, maxY)) continue;
                queue.add(new int[]{x, y, z});
                if (d > far) cands.add(new double[]{x, y, z, Math.min(d, max)});
            }
        }
        cands.sort((a, b) -> Double.compare(b[3], a[3]));
        int checks = 0;
        for (double[] c : cands) {
            if (c[3] <= far) break;
            double cx = c[0] + 0.5, cy = c[1] + 0.5, cz = c[2] + 0.5;
            for (double[] p : pts) {
                if (++checks > MAX_CHECKS) return c[3];
                double px = j.x + p[0] - cx, py = j.y + p[1] - cy, pz = j.z + p[2] - cz;
                double len = Math.sqrt(px * px + py * py + pz * pz);
                if (len < 1e-6) return c[3];
                double[] dir = {px / len, py / len, pz / len};
                // in plain sight: nothing opaque between the cell's middle and the point (the point's own block aside)
                if (ray(cx, cy, cz, dir, len, chunks, opaque, minY, maxY, 0) >= len - 0.5) return c[3];
            }
        }
        return far;
    }

    static long cell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    /**
     * Where rays start: the origin, plus (with a model box) a grid through the box, every ~0.75 blocks up to 5 points
     * a side (corners, edges, faces and inside), as offsets from the entity.
     */
    static List<double[]> samples(float[] box) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[]{0, 0, 0});
        if (box == null) return out;
        int[] n = new int[3];
        for (int a = 0; a < 3; a++) {
            double ext = box[a + 3] - box[a];
            n[a] = ext < 0.3 ? 1 : (int) Math.min(5, Math.max(2, Math.ceil(ext / 0.75) + 1));
        }
        for (int ix = 0; ix < n[0]; ix++)
            for (int iy = 0; iy < n[1]; iy++)
                for (int iz = 0; iz < n[2]; iz++)
                    out.add(new double[]{at(box[0], box[3], ix, n[0]), at(box[1], box[4], iy, n[1]), at(box[2], box[5], iz, n[2])});
        return out;
    }

    private static double at(float lo, float hi, int i, int n) {
        return n == 1 ? (lo + hi) / 2.0 : lo + (hi - lo) * i / (n - 1.0);
    }

    static boolean opaqueAt(double x, double y, double z, Map<Long, ChunkSnapshot> chunks, boolean[] opaque, int minY, int maxY) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (by < minY || by >= maxY) return false;
        ChunkSnapshot c = chunks.get(key(bx >> 4, bz >> 4));
        return c != null && opaque[c.getBlockType(bx & 15, by, bz & 15).ordinal()];
    }

    /** How far a ray from (x, y, z) along d gets before an opaque block (or max). Voxel stepping (Amanatides-Woo). */
    static double ray(double x, double y, double z, double[] d, double max, Map<Long, ChunkSnapshot> chunks,
                      boolean[] opaque, int minY, int maxY, double skip) {
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
            if (t < skip) continue;            // the display's own block(s), from its origin
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
