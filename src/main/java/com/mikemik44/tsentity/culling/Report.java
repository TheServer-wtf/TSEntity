package com.mikemik44.tsentity.culling;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Which models in the packs will get a loose culling box, measured as a plain item display would show them (no
 * transform, no rotation): how far each hangs below the entity (the box can't reach there) and how far its middle
 * sits off the entity (the square box grows to the far side). Re-centring those models in the pack fixes them for
 * every display at once, without moving any entities. Writes report.csv in the plugin's folder; chat gets the worst.
 */
final class Report {

    record Row(String id, float above, float below, float offset, float box, float tight, String problems) {}

    static void run(Culling plugin, CommandSender s) {
        List<Row> rows = new ArrayList<>();
        // as the display renderer draws a NONE-context item: turned 180°, model units centred on the entity
        Matrix4f m = new Matrix4f().rotateY((float) Math.PI).translate(-0.5f, -0.5f, -0.5f).scale(1 / 16f);
        for (String id : plugin.models.itemIds()) {
            Models.Shape shape = plugin.models.item(id);
            if (shape == null || shape.corners().isEmpty()) continue;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, maxH = 0;
            Vector3f v = new Vector3f();
            for (Vector3f c : shape.corners()) {
                m.transformPosition(c, v);
                minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
                minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
                minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
                maxH = Math.max(maxH, Math.max(Math.abs(v.x), Math.abs(v.z)));
            }
            rows.add(new Row(id, Math.max(0, maxY), Math.max(0, -minY - Culling.PAD),
                    (float) Math.hypot((minX + maxX) / 2, (minZ + maxZ) / 2),
                    Math.max(Culling.MIN, 2 * maxH - 2 * Culling.PAD),
                    Math.max(Culling.MIN, Math.max(maxX - minX, maxZ - minZ) - 2 * Culling.PAD), String.join("; ", shape.problems())));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -r.below()).thenComparingDouble(r -> -r.offset()));

        File out = new File(plugin.plugin.getDataFolder(), "report.csv");
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.println("item_model,height_above,hangs_below_box,off_centre,box_width,tight_width,problems");
            for (Row r : rows) {
                w.printf(Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.3f,%.3f,\"%s\"%n", r.id(), r.above(), r.below(), r.offset(),
                        r.box(), r.tight(), r.problems().replace("\"", "'"));
            }
        } catch (Exception e) {
            s.sendMessage(Component.text("Couldn't write report.csv: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        long hanging = rows.stream().filter(r -> r.below() > 0.01).count();
        long off = rows.stream().filter(r -> r.offset() > 0.05).count();
        long broken = rows.stream().filter(r -> !r.problems().isEmpty()).count();
        s.sendMessage(Component.text(String.format(Locale.ROOT,
                "%d models: %d hang below the entity, %d sit off centre, %d couldn't be read fully. Full list: %s",
                rows.size(), hanging, off, broken, out.getPath()), NamedTextColor.GREEN));
        int shown = 0;
        for (Row r : rows) {
            if (shown++ >= 10 || (r.below() <= 0.01 && r.offset() <= 0.05)) break;
            s.sendMessage(Component.text(String.format(Locale.ROOT, " • %s: %.2f below, %.2f off centre (box %.2f, could be %.2f)",
                    r.id(), r.below(), r.offset(), r.box(), r.tight()), NamedTextColor.YELLOW));
        }
    }
}
