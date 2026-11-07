package com.mikemik44.tsentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.mikemik44.tsentity.culling.Culling;
import com.mikemik44.tsentity.culling.Models;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * /colorslider: a window to colour a tinted prop, the item in hand or the item display looked at. One section per
 * tint slot the item's definition reads (custom_model_data colours, or dyed_color for a dye tint): red / green /
 * blue sliders and a hex box (a valid hex wins over the sliders when it was changed), with a preview of the item.
 * Under each slot a preset list (the 16 dye colours and TheServer's 16 custom ones). Apply colours every slot: a
 * changed hex first, else a picked preset, else the sliders. Both reopen the window on the result (dialogs only report values on a click, so a live
 * slider preview isn't possible). Done closes it.
 */
final class ColorSlider {

    /** name, colour: the dye colours, then TheServer's custom set (same order as the pack's colour variants). */
    static final String[][] SWATCHES = {
            {"White", "F9FFFE"}, {"Light Gray", "9D9D97"}, {"Gray", "474F52"}, {"Black", "1D1D21"},
            {"Brown", "835432"}, {"Red", "B02E26"}, {"Orange", "F9801D"}, {"Yellow", "FED83D"},
            {"Lime", "80C71F"}, {"Green", "5E7C16"}, {"Cyan", "169C9C"}, {"Light Blue", "3AB3DA"},
            {"Blue", "3C44AA"}, {"Purple", "8932B8"}, {"Magenta", "C74EBD"}, {"Pink", "F38BAA"},
            {"Maroon", "6B1E2A"}, {"Coral", "F07A6A"}, {"Ginger", "C8692B"}, {"Tan", "D2B48C"},
            {"Chartreuse", "B4D23C"}, {"Olive", "6B6B2A"}, {"Mint", "8FE3B5"}, {"Forest", "2E5A34"},
            {"Teal", "1F7A7A"}, {"Navy", "1F2A5A"}, {"Azure", "4A90D9"}, {"Slate", "5A6672"},
            {"Periwinkle", "8C93E6"}, {"Lavender", "B9A3E3"}, {"Mauve", "A8749B"}, {"Rose", "E07A9A"},
    };

    private final Culling culling;

    ColorSlider(Culling culling) {
        this.culling = culling;
    }

    void open(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!tints(hand).isEmpty()) {
            show(p, hand.clone(), it -> p.getInventory().setItemInMainHand(it));
            return;
        }
        ItemDisplay d = looked(p);
        if (d != null && !tints(d.getItemStack()).isEmpty()) {
            show(p, d.getItemStack().clone(), it -> { if (d.isValid()) d.setItemStack(it); });
            return;
        }
        p.sendMessage(Component.text("Hold a tintable item, or look at an item display showing one.", NamedTextColor.RED));
    }

    List<Models.Tint> tints(ItemStack it) {
        if (it == null || it.getType().isAir()) return List.of();
        Key model = it.getData(DataComponentTypes.ITEM_MODEL);
        String id = model != null ? model.asString() : "minecraft:" + it.getType().getKey().getKey();
        return culling.models().tints(id);
    }

    /** The colour a slot shows now: the item's own value, else the definition's default. */
    static int current(ItemStack it, Models.Tint t) {
        if (t.dye()) {
            DyedItemColor dc = it.getData(DataComponentTypes.DYED_COLOR);
            return dc != null ? dc.color().asRGB() : t.defaultRgb();
        }
        CustomModelData cmd = it.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd != null && t.cmdIndex() < cmd.colors().size()) return cmd.colors().get(t.cmdIndex()).asRGB();
        return t.defaultRgb();
    }

    /** The item with these slots (index into tints -> 0xRRGGBB) recoloured; other slots and data kept. */
    static ItemStack recolor(ItemStack item, List<Models.Tint> tints, Map<Integer, Integer> set) {
        ItemStack out = item.clone();
        CustomModelData old = out.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        List<Color> colors = new ArrayList<>();
        if (old != null) colors.addAll(old.colors());
        boolean cmdTouched = false;
        for (var en : set.entrySet()) {
            Models.Tint t = tints.get(en.getKey());
            if (t.dye()) {
                out.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(en.getValue())));
            } else {
                while (colors.size() <= t.cmdIndex()) colors.add(Color.fromRGB(tints.stream()
                        .filter(x -> !x.dye() && x.cmdIndex() == colors.size()).findFirst()
                        .map(Models.Tint::defaultRgb).orElse(0xFFFFFF)));
                colors.set(t.cmdIndex(), Color.fromRGB(en.getValue()));
                cmdTouched = true;
            }
        }
        if (cmdTouched) {
            CustomModelData.Builder b = CustomModelData.customModelData().addColors(colors);
            if (old != null) { b.addFloats(old.floats()); b.addFlags(old.flags()); b.addStrings(old.strings()); }
            out.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
        }
        return out;
    }

    void show(Player p, ItemStack item, Consumer<ItemStack> write) {
        List<Models.Tint> tints = tints(item);
        List<DialogInput> inputs = new ArrayList<>();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(item).build());
        for (int i = 0; i < tints.size(); i++) {
            int rgb = current(item, tints.get(i));
            String hex = String.format(Locale.ROOT, "#%06X", rgb);
            body.add(DialogBody.plainMessage(Component.text("Slot " + (i + 1) + (tints.get(i).dye() ? " (dye)" : "") + "  ")
                    .append(Component.text("███ " + hex, TextColor.color(rgb)))));
            inputs.add(DialogInput.numberRange("r" + i, Component.text("Slot " + (i + 1) + " red"), 0f, 255f)
                    .step(1f).initial((float) ((rgb >> 16) & 255)).width(300).build());
            inputs.add(DialogInput.numberRange("g" + i, Component.text("Slot " + (i + 1) + " green"), 0f, 255f)
                    .step(1f).initial((float) ((rgb >> 8) & 255)).width(300).build());
            inputs.add(DialogInput.numberRange("b" + i, Component.text("Slot " + (i + 1) + " blue"), 0f, 255f)
                    .step(1f).initial((float) (rgb & 255)).width(300).build());
            inputs.add(DialogInput.text("hex" + i, Component.text("Slot " + (i + 1) + " hex")).initial(hex).maxLength(7)
                    .width(120).build());
            List<SingleOptionDialogInput.OptionEntry> presets = new ArrayList<>();
            presets.add(SingleOptionDialogInput.OptionEntry.create("keep", Component.text("sliders / hex"), true));
            for (String[] sw : SWATCHES) {
                presets.add(SingleOptionDialogInput.OptionEntry.create(sw[1],
                        Component.text("██ ", TextColor.color(Integer.parseInt(sw[1], 16))).append(Component.text(sw[0])),
                        false));
            }
            inputs.add(DialogInput.singleOption("preset" + i, Component.text("Slot " + (i + 1) + " preset"), presets)
                    .width(200).build());
        }

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(Component.text("Apply"), Component.text("Colour every slot: its new hex, else its preset, else its sliders"),
                130, DialogAction.customClick((view, audience) -> {
                    ItemStack out = recolor(item, tints, fromSliders(view, item, tints));
                    write.accept(out);
                    show(p, out, write);
                }, ClickCallback.Options.builder().uses(1).build())));
        ActionButton done = ActionButton.create(Component.text("Done"), null, 100, null);
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text("Colour slider")).body(body).inputs(inputs)
                        .canCloseWithEscape(true).build())
                .type(DialogType.multiAction(buttons).columns(1).exitAction(done).build()));
        p.showDialog(dialog);
    }

    /** Each slot's colour from its sliders, or its hex box when that was changed to a valid hex. */
    static Map<Integer, Integer> fromSliders(DialogResponseView view, ItemStack item, List<Models.Tint> tints) {
        Map<Integer, Integer> set = new HashMap<>();
        for (int i = 0; i < tints.size(); i++) {
            String was = String.format(Locale.ROOT, "#%06X", current(item, tints.get(i)));
            String hex = view.getText("hex" + i);
            String preset = view.getText("preset" + i);
            if (hex != null && !hex.equalsIgnoreCase(was) && hex.matches("#?[0-9a-fA-F]{6}")) {
                set.put(i, Integer.parseInt(hex.replace("#", ""), 16));
            } else if (preset != null && preset.matches("[0-9A-Fa-f]{6}")) {
                set.put(i, Integer.parseInt(preset, 16));
            } else {
                set.put(i, (Math.round(view.getFloat("r" + i)) << 16) | (Math.round(view.getFloat("g" + i)) << 8)
                        | Math.round(view.getFloat("b" + i)));
            }
        }
        return set;
    }

    /**
     * Axiom (and similar placers) put a display down from the held item but keep only its id and model, dropping the
     * colours. A display that appears with a tinted model and no colours of its own takes them from a player nearby
     * holding that same model (main hand first, then off hand). A display that has colours is never touched.
     */
    void copyHeldTint(ItemDisplay d) {
        ItemStack shown = d.getItemStack();
        if (shown == null || shown.getType().isAir() || tints(shown).isEmpty()) return;
        CustomModelData has = shown.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        // Axiom places from a client-side copy that can carry an older tint, so the held item's tint wins even when
        // the display already has one (only on spawn, for a player within 8 blocks holding that same model)
        Key model = shown.getData(DataComponentTypes.ITEM_MODEL);
        for (Entity e : d.getNearbyEntities(8, 8, 8)) {
            if (!(e instanceof Player p)) continue;
            for (ItemStack held : new ItemStack[] {p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
                if (held == null || held.getType().isAir()) continue;
                Key hm = held.getData(DataComponentTypes.ITEM_MODEL);
                if (model == null || !model.equals(hm)) continue;
                CustomModelData hc = held.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                DyedItemColor hd = held.getData(DataComponentTypes.DYED_COLOR);
                if ((hc == null || hc.colors().isEmpty()) && hd == null) continue;
                if (hc != null && has != null && hc.colors().equals(has.colors())
                        && java.util.Objects.equals(hd, shown.getData(DataComponentTypes.DYED_COLOR))) return;
                ItemStack out = shown.clone();
                if (hc != null && !hc.colors().isEmpty()) {
                    CustomModelData.Builder b = CustomModelData.customModelData().addColors(hc.colors());
                    if (has != null) { b.addFloats(has.floats()); b.addFlags(has.flags()); b.addStrings(has.strings()); }
                    out.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
                }
                if (hd != null) out.setData(DataComponentTypes.DYED_COLOR, hd);
                d.setItemStack(out);
                return;
            }
        }
    }

    /** The item display nearest the player's line of sight, within 8 blocks. */
    static ItemDisplay looked(Player p) {
        Vector eye = p.getEyeLocation().toVector(), dir = p.getEyeLocation().getDirection();
        ItemDisplay best = null;
        double bestOff = 1.5;
        for (Entity e : p.getNearbyEntities(8, 8, 8)) {
            if (!(e instanceof ItemDisplay d)) continue;
            Vector to = d.getLocation().toVector().subtract(eye);
            double along = to.dot(dir);
            if (along <= 0) continue;
            double off = to.clone().subtract(dir.clone().multiply(along)).length();
            if (off < bestOff) { bestOff = off; best = d; }
        }
        return best;
    }
}
