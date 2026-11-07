package com.mikemik44.tsentity.culling;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Reads models out of resource packs, the way the client would: an item model id (`ns:path`) -> its item definition
 * (`assets/ns/items/path.json`) -> every model that definition can show (conditions, selects and ranges all count, so
 * the box fits whichever one is on screen) -> each model's elements through its parents. The result is the model's
 * corners in block units, centred like the client centres an item, plus its display transforms per context.
 */
public final class Models {

    /** A model as the client would draw it: its corners (model units, 0-16 a block) and display transforms. */
    record Shape(List<Vector3f> corners, Map<String, JsonObject> display, List<String> models, List<String> problems) {}

    private final List<File> packs;
    private final Map<String, Shape> cache = new HashMap<>();

    Models(List<File> packs) {
        this.packs = packs;
    }

    void clear() {
        cache.clear();
    }

    /** The item's custom model data, as the item definition's custom_model_data properties read it. */
    record Cmd(List<Float> floats, List<Boolean> flags, List<String> strings) {
        static final Cmd NONE = new Cmd(List.of(), List.of(), List.of());
    }

    /** Every model an item could show, whatever its data (for the report). */
    Shape item(String id) {
        return cache.computeIfAbsent(id + "|*", k -> load(id, null));
    }

    /** The model an item shows with this custom model data, or null if no pack has the item. */
    Shape item(String id, Cmd cmd) {
        return cache.computeIfAbsent(id + "|" + cmd, k -> load(id, cmd));
    }

    private Shape load(String id, Cmd cmd) {
        String[] k = split(id);
        JsonObject def = json("assets/" + k[0] + "/items/" + k[1] + ".json");
        if (def == null) return null;
        Set<String> refs = new java.util.LinkedHashSet<>();
        if (cmd == null) collect(def.get("model"), refs);
        else pick(def.get("model"), cmd, refs);
        List<Vector3f> corners = new ArrayList<>();
        Map<String, JsonObject> display = new HashMap<>();
        List<String> problems = new ArrayList<>();
        List<String> used = new ArrayList<>(refs);
        for (String ref : used) {
            Resolved r = resolve(ref, problems);
            corners.addAll(r.corners);
            r.display.forEach(display::putIfAbsent);
        }
        if (refs.isEmpty()) problems.add("its item definition names no model (special or empty)");
        return new Shape(corners, display, used, problems);
    }

    /**
     * The model(s) this item definition shows for the given custom model data, the way the client picks: a
     * range_dispatch takes the entry with the highest threshold at or under the value, a select the case that matches,
     * a condition its branch. Properties other than custom_model_data (damage, time, ...) can't be known here, so
     * every branch of those counts, as does each part of a composite.
     */
    private static void pick(JsonElement e, Cmd cmd, Set<String> out) {
        if (e == null || !e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        String prop = o.has("property") ? o.get("property").getAsString().replace("minecraft:", "") : "";
        int index = o.has("index") ? o.get("index").getAsInt() : 0;
        switch (type) {
            case "model" -> { if (o.has("model")) out.add(o.get("model").getAsString()); }
            case "special" -> { if (o.has("base")) out.add(o.get("base").getAsString()); }
            case "composite" -> { if (o.has("models")) for (JsonElement m : o.getAsJsonArray("models")) pick(m, cmd, out); }
            case "range_dispatch" -> {
                if (!prop.equals("custom_model_data")) { collect(e, out); return; }
                float scale = o.has("scale") ? o.get("scale").getAsFloat() : 1;
                float value = (index < cmd.floats().size() ? cmd.floats().get(index) : 0) * scale;
                JsonElement chosen = o.get("fallback");
                float best = -Float.MAX_VALUE;
                if (o.has("entries")) {
                    for (JsonElement en : o.getAsJsonArray("entries")) {
                        float th = en.getAsJsonObject().get("threshold").getAsFloat();
                        if (th <= value && th >= best) { best = th; chosen = en.getAsJsonObject().get("model"); }
                    }
                }
                pick(chosen, cmd, out);
            }
            case "select" -> {
                if (!prop.equals("custom_model_data")) { collect(e, out); return; }
                String value = index < cmd.strings().size() ? cmd.strings().get(index) : null;
                JsonElement chosen = o.get("fallback");
                if (value != null && o.has("cases")) {
                    for (JsonElement c : o.getAsJsonArray("cases")) {
                        JsonElement when = c.getAsJsonObject().get("when");
                        boolean hit = when.isJsonArray()
                                ? when.getAsJsonArray().asList().stream().anyMatch(w -> w.getAsString().equals(value))
                                : when.getAsString().equals(value);
                        if (hit) { chosen = c.getAsJsonObject().get("model"); break; }
                    }
                }
                pick(chosen, cmd, out);
            }
            case "condition" -> {
                if (!prop.equals("custom_model_data")) { collect(e, out); return; }
                boolean flag = index < cmd.flags().size() && cmd.flags().get(index);
                pick(o.get(flag ? "on_true" : "on_false"), cmd, out);
            }
            default -> collect(e, out);
        }
    }

    /** Every model an item definition can show: `model` types, and a `special` type's `base`. */
    private static void collect(JsonElement e, Set<String> out) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) collect(c, out);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        if (type.equals("model") && o.has("model") && o.get("model").isJsonPrimitive()) out.add(o.get("model").getAsString());
        if (type.equals("special") && o.has("base")) out.add(o.get("base").getAsString());
        for (var entry : o.entrySet()) if (!entry.getKey().equals("type")) collect(entry.getValue(), out);
    }

    private record Resolved(List<Vector3f> corners, Map<String, JsonObject> display) {}

    /** A model's elements and display transforms through its parents (the nearest elements / display entry wins). */
    private Resolved resolve(String ref, List<String> problems) {
        List<Vector3f> corners = null;
        Map<String, JsonObject> display = new HashMap<>();
        String at = ref;
        for (int depth = 0; at != null && depth < 32; depth++) {
            String plain = at.replace("minecraft:", "");
            if (plain.equals("builtin/generated") || plain.equals("item/generated") || plain.equals("item/handheld")) {
                // a flat sprite: the full 16x16 face, one pixel thick
                if (corners == null) corners = box(0, 0, 7.5f, 16, 16, 8.5f);
                if (!plain.startsWith("builtin")) { at = "minecraft:builtin/generated"; }
                // keep walking for vanilla's own display transforms only if the pack has the file
            }
            String[] k = split(at);
            JsonObject m = json("assets/" + k[0] + "/models/" + k[1] + ".json");
            if (m == null) {
                if (corners == null && !plain.startsWith("builtin")) problems.add("model " + at + " isn't in the packs");
                break;
            }
            if (corners == null && m.has("elements")) corners = elements(m.getAsJsonArray("elements"));
            if (m.has("display")) {
                for (var d : m.getAsJsonObject("display").entrySet()) display.putIfAbsent(d.getKey(), d.getValue().getAsJsonObject());
            }
            at = m.has("parent") ? m.get("parent").getAsString() : null;
        }
        if (corners == null) {
            problems.add("model " + ref + " has no elements (a vanilla parent the packs don't include?)");
            corners = box(0, 0, 0, 16, 16, 16);
        }
        return new Resolved(corners, display);
    }

    private static List<Vector3f> elements(JsonArray els) {
        List<Vector3f> out = new ArrayList<>();
        for (JsonElement e : els) {
            JsonObject el = e.getAsJsonObject();
            float[] f = floats(el.getAsJsonArray("from")), t = floats(el.getAsJsonArray("to"));
            List<Vector3f> c = box(f[0], f[1], f[2], t[0], t[1], t[2]);
            if (el.has("rotation")) {
                JsonObject r = el.getAsJsonObject("rotation");
                float[] o = r.has("origin") ? floats(r.getAsJsonArray("origin")) : new float[]{8, 8, 8};
                float angle = (float) Math.toRadians(r.has("angle") ? r.get("angle").getAsFloat() : 0);
                String axis = r.has("axis") ? r.get("axis").getAsString() : "y";
                Quaternionf q = switch (axis) {
                    case "x" -> new Quaternionf().rotationX(angle);
                    case "z" -> new Quaternionf().rotationZ(angle);
                    default -> new Quaternionf().rotationY(angle);
                };
                for (Vector3f v : c) v.sub(o[0], o[1], o[2]).rotate(q).add(o[0], o[1], o[2]);
            }
            out.addAll(c);
        }
        return out;
    }

    static List<Vector3f> box(float x0, float y0, float z0, float x1, float y1, float z1) {
        List<Vector3f> c = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            c.add(new Vector3f((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1));
        }
        return c;
    }

    static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    private static String[] split(String id) {
        int i = id.indexOf(':');
        return i < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, i), id.substring(i + 1)};
    }

    /**
     * The colour slots an item definition reads (its "tints"): a custom_model_data tint (index into the item's
     * custom_model_data colors) or a dye tint (dyed_color), with its default colour as 0xRRGGBB. In tintindex order.
     */
    public record Tint(int tintIndex, boolean dye, int cmdIndex, int defaultRgb) {}

    public List<Tint> tints(String id) {
        String[] k = split(id);
        JsonObject def = json("assets/" + k[0] + "/items/" + k[1] + ".json");
        List<Tint> out = new ArrayList<>();
        if (def != null) findTints(def.get("model"), out);
        return out;
    }

    private static void findTints(JsonElement e, List<Tint> out) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) findTints(c, out); return; }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("tints") && o.get("tints").isJsonArray() && out.isEmpty()) {
            JsonArray ts = o.getAsJsonArray("tints");
            for (int i = 0; i < ts.size(); i++) {
                JsonObject t = ts.get(i).getAsJsonObject();
                String type = t.has("type") ? t.get("type").getAsString().replace("minecraft:", "") : "";
                int def = t.has("default") ? rgb(t.get("default")) : 0xFFFFFF;
                if (type.equals("custom_model_data")) out.add(new Tint(i, false, t.has("index") ? t.get("index").getAsInt() : 0, def));
                else if (type.equals("dye")) out.add(new Tint(i, true, -1, def));
            }
        }
        for (var en : o.entrySet()) if (!en.getKey().equals("tints")) findTints(en.getValue(), out);
    }

    /** A tint default: [r, g, b] floats 0-1, or a packed int. */
    static int rgb(JsonElement v) {
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            int r = Math.round(a.get(0).getAsFloat() * 255), g = Math.round(a.get(1).getAsFloat() * 255),
                    b = Math.round(a.get(2).getAsFloat() * 255);
            return (r << 16) | (g << 8) | b;
        }
        return v.getAsInt() & 0xFFFFFF;
    }

    /** Every item model id the packs define (`assets/<ns>/items/<path>.json`). */
    List<String> itemIds() {
        Set<String> ids = new java.util.TreeSet<>();
        for (File pack : packs) {
            try {
                if (pack.isDirectory()) {
                    File assets = new File(pack, "assets");
                    File[] spaces = assets.listFiles(File::isDirectory);
                    if (spaces == null) continue;
                    for (File ns : spaces) {
                        java.nio.file.Path items = new File(ns, "items").toPath();
                        if (!Files.isDirectory(items)) continue;
                        try (var walk = Files.walk(items)) {
                            walk.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                                String rel = items.relativize(f).toString().replace(File.separatorChar, '/');
                                ids.add(ns.getName() + ":" + rel.substring(0, rel.length() - 5));
                            });
                        }
                    }
                } else if (pack.isFile()) {
                    try (ZipFile z = new ZipFile(pack)) {
                        z.stream().map(ZipEntry::getName)
                                .filter(n -> n.startsWith("assets/") && n.endsWith(".json") && n.split("/").length > 3
                                        && n.split("/")[2].equals("items"))
                                .forEach(n -> {
                                    String[] parts = n.split("/", 4);
                                    ids.add(parts[1] + ":" + parts[3].substring(0, parts[3].length() - 5));
                                });
                    }
                }
            } catch (Exception ignored) {
                // an unreadable pack lists nothing
            }
        }
        return new ArrayList<>(ids);
    }

    /** A file from the first pack (folder or zip) that has it. */
    private JsonObject json(String path) {
        for (File pack : packs) {
            try {
                if (pack.isDirectory()) {
                    File f = new File(pack, path);
                    if (f.isFile()) return JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
                } else if (pack.isFile()) {
                    try (ZipFile z = new ZipFile(pack)) {
                        ZipEntry e = z.getEntry(path);
                        if (e == null) continue;
                        try (InputStream in = z.getInputStream(e)) {
                            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                        }
                    }
                }
            } catch (Exception ignored) {
                // a broken file reads as missing; the problems list says which model
            }
        }
        return null;
    }
}
