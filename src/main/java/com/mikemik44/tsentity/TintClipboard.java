package com.mikemik44.tsentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mikemik44.tsentity.culling.Models;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * /tintclipboard (or /paintbrush): the Paint Brush, a brush that copies tint colours from one tinted prop and paints
 * them onto another (it replaced the map-looking Tint Clipboard; old clipboards still work). Sneak + right-click = copy from the display you look at (or the item in
 * your off hand); right-click = paste onto it. Slots are copied in order, so a red sofa's colours go onto a blue sofa,
 * or slot 1 of any prop onto slot 1 of another. The colours live on the clipboard (its lore shows them).
 */
final class TintClipboard implements Listener {

    private final ColorSlider slider;
    private final NamespacedKey key;

    TintClipboard(Plugin plugin, ColorSlider slider) {
        this.slider = slider;
        this.key = new NamespacedKey(plugin, "tint_clipboard");
    }

    ItemStack item() {
        ItemStack it = new ItemStack(org.bukkit.Material.BRUSH);
        it.setData(DataComponentTypes.ITEM_NAME, Component.text("Paint Brush", NamedTextColor.AQUA));
        it.editPersistentDataContainer(p -> p.set(key, PersistentDataType.INTEGER_ARRAY, new int[0]));
        lore(it, new int[0]);
        return it;
    }

    private boolean isClipboard(ItemStack it) {
        return it != null && it.getPersistentDataContainer().has(key, PersistentDataType.INTEGER_ARRAY);
    }

    private static void lore(ItemStack it, int[] colors) {
        List<Component> lines = new ArrayList<>();
        if (colors.length == 0) {
            lines.add(Component.text("Empty: sneak + right-click a tinted prop", NamedTextColor.GRAY));
        } else {
            for (int i = 0; i < colors.length; i++) {
                lines.add(Component.text("Slot " + (i + 1) + "  ", NamedTextColor.GRAY)
                        .append(Component.text("■■■ ", TextColor.color(colors[i])))
                        .append(Component.text(String.format("#%06X", colors[i]), TextColor.color(colors[i])))
                        .decoration(TextDecoration.ITALIC, false));
            }
            lines.add(Component.text("Right-click a prop to paint, sneak to pick colours again", NamedTextColor.DARK_GRAY));
        }
        it.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack clip = p.getInventory().getItemInMainHand();
        if (!isClipboard(clip)) return;
        e.setCancelled(true);
        ItemDisplay d = ColorSlider.looked(p);
        ItemStack target = d != null ? d.getItemStack() : p.getInventory().getItemInOffHand();
        List<Models.Tint> tints = target == null || target.getType().isAir() ? List.of() : slider.tints(target);
        if (tints.isEmpty()) {
            p.sendActionBar(Component.text("Look at a tinted prop (or hold one in your off hand)", NamedTextColor.GRAY));
            return;
        }
        if (p.isSneaking()) {                      // copy
            int[] colors = new int[tints.size()];
            for (int i = 0; i < colors.length; i++) colors[i] = ColorSlider.current(target, tints.get(i));
            ItemStack out = clip.clone();
            out.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.INTEGER_ARRAY, colors));
            lore(out, colors);
            p.getInventory().setItemInMainHand(out);
            p.sendActionBar(Component.text("Copied " + colors.length + " colour(s)", NamedTextColor.AQUA));
            return;
        }
        int[] colors = clip.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);   // paste
        if (colors == null || colors.length == 0) {
            p.sendActionBar(Component.text("No colours on the brush: sneak + right-click a prop to pick them up", NamedTextColor.GRAY));
            return;
        }
        java.util.function.Consumer<Map<Integer, Integer>> apply = set -> {
            ItemStack now = d != null ? d.getItemStack() : p.getInventory().getItemInOffHand();
            ItemStack recoloured = ColorSlider.recolor(now, tints, set);
            if (d != null) d.setItemStack(recoloured);
            else p.getInventory().setItemInOffHand(recoloured);
            p.sendActionBar(Component.text("Painted " + set.size() + " colour(s)", NamedTextColor.AQUA));
        };
        if (colors.length == tints.size()) {   // same number of slots: straight across, no questions
            Map<Integer, Integer> set = new HashMap<>();
            for (int i = 0; i < colors.length; i++) set.put(i, colors[i]);
            apply.accept(set);
            return;
        }
        picker(p, target, tints, colors, apply);
    }

    /** Which copied colour goes into which slot: one dropdown per slot of the prop (default: the same slot number,
     *  or keep when the clipboard has fewer), then Paste. */
    private void picker(Player p, ItemStack target, List<Models.Tint> tints, int[] colors,
                        java.util.function.Consumer<Map<Integer, Integer>> apply) {
        List<io.papermc.paper.registry.data.dialog.input.DialogInput> inputs = new ArrayList<>();
        for (int i = 0; i < tints.size(); i++) {
            List<io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry> opts = new ArrayList<>();
            int now = ColorSlider.current(target, tints.get(i));
            opts.add(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create("keep",
                    Component.text("keep ").append(Component.text("■", TextColor.color(now))), i >= colors.length));
            for (int k = 0; k < colors.length; k++) {
                opts.add(io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry.create("c" + k,
                        Component.text("copied " + (k + 1) + " ").append(Component.text("■■", TextColor.color(colors[k]))),
                        k == i));
            }
            inputs.add(io.papermc.paper.registry.data.dialog.input.DialogInput.singleOption("slot" + i,
                    Component.text("Slot " + (i + 1)), opts).width(220).build());
        }
        var paste = io.papermc.paper.registry.data.dialog.ActionButton.create(Component.text("Paste"), null, 120,
                io.papermc.paper.registry.data.dialog.action.DialogAction.customClick((view, audience) -> {
                    Map<Integer, Integer> set = new HashMap<>();
                    for (int i = 0; i < tints.size(); i++) {
                        String v = view.getText("slot" + i);
                        if (v != null && v.startsWith("c")) set.put(i, colors[Integer.parseInt(v.substring(1))]);
                    }
                    apply.accept(set);
                }, net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).build()));
        var cancel = io.papermc.paper.registry.data.dialog.ActionButton.create(Component.text("Cancel"), null, 100, null);
        p.showDialog(io.papermc.paper.dialog.Dialog.create(f -> f.empty()
                .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(Component.text("Paste tints"))
                        .body(List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.item(target).build()))
                        .inputs(inputs).canCloseWithEscape(true).build())
                .type(io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(paste, cancel))));
    }
}
