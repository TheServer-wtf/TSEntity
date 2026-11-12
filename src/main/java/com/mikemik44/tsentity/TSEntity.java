package com.mikemik44.tsentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import com.mikemik44.tsentity.culling.Culling;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

public final class TSEntity extends JavaPlugin implements Listener, TabCompleter {

	private static final Color GREEN_GLOW = Color.fromARGB(255, 50, 255, 50);
	private static final Color YELLOW_GLOW = Color.fromARGB(255, 255, 255, 50);

	private static final long WAND_COOLDOWN_MS = 250L;
	// current green selected entity
	private final Map<UUID, UUID> selectedEntities = new HashMap<>();

	// pending passenger queue for each player, queue.get(0) is current yellow
	private final Map<UUID, List<UUID>> pendingPassengers = new HashMap<>();

	// reused view range
	private final Map<UUID, Float> selectedDist = new HashMap<>();

	// wand cooldown
	private final Map<UUID, Long> lastWandUse = new HashMap<>();

	// players currently doing manual step-through
	private final Set<UUID> manualMode = new HashSet<>();

	// culling boxes (display width/height) fitted from the resource pack's models: see culling/Culling.java
	private Culling culling;
	private Frames frames;
	private CullRange cullRange;
	private Variants variants;
	private ColorSlider colorSlider;
	private TintClipboard tintClipboard;

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equalsIgnoreCase("tsentity")) {
			return Collections.emptyList();
		}

		if (args.length == 1) {
			List<String> all = new ArrayList<>(List.of("wand", "cancel", "confirm", "change", "deny", "next", "debug",
					"offset", "skip", "frames", "variant", "cullrange"));
			all.addAll(Culling.SUBCOMMANDS);
			return filterTabs(args[0], all.toArray(new String[0]));
		}

		if (Culling.SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
			return culling.onTabComplete(sender, args);
		}
		if (args[0].equalsIgnoreCase("variant")) {
			if (args.length == 2) {
				return filterTabs(args[1], "old", "new", "convert", "undo", "list", "remove");
			}
			if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
				return filterTabs(args[2], variants.names().toArray(new String[0]));
			}
			if (args.length >= 3 && (args[1].equalsIgnoreCase("convert") || args[1].equalsIgnoreCase("undo"))) {
				return filterTabs(args[args.length - 1], "16", "32", "64", "confirm");
			}
			return Collections.emptyList();
		}
		if (args[0].equalsIgnoreCase("cullrange")) {
			return filterTabs(args[args.length - 1], "16", "32", "64", "confirm", "undo");
		}
		if (args[0].equalsIgnoreCase("frames")) {
			return filterTabs(args[args.length - 1], "16", "32", "64", "confirm", "undo");
		}

		if (args.length == 2) {
			if (args[0].equalsIgnoreCase("debug")) {
				return filterTabs(args[1], "enable", "disable");
			}
		}

		return Collections.emptyList();
	}

	private List<String> filterTabs(String input, String... options) {
		List<String> result = new ArrayList<>();
		String check = input == null ? "" : input.toLowerCase(Locale.ROOT);

		for (String option : options) {
			if (option.toLowerCase(Locale.ROOT).startsWith(check)) {
				result.add(option);
			}
		}

		return result;
	}

	@Override
	public void onEnable() {

		saveDefaultConfig();
		getServer().getPluginManager().registerEvents(this, this);
		culling = new Culling(this);
		culling.enable();
		variants = new Variants(this, culling::modelKey, d -> culling.apply(d, false, false), culling::itemMatrix);
		frames = new Frames(this, variants, culling);
		cullRange = new CullRange(this, culling);
		colorSlider = new ColorSlider(culling);
		tintClipboard = new TintClipboard(this, colorSlider);
		getServer().getPluginManager().registerEvents(tintClipboard, this);
		if (getCommand("tsentity") != null) {
			getCommand("tsentity").setTabCompleter(this);
		}
		getLogger().info("Plugin TSEntity Loaded");
	}

	@Override
	public void onDisable() {
		getLogger().info("Plugin TSEntity Disabling");
		for (Player player : Bukkit.getOnlinePlayers()) {
			clearPending(player);
			manualMode.remove(player.getUniqueId());

			UUID selectedId = selectedEntities.remove(player.getUniqueId());
			if (selectedId == null) {
				continue;
			}

			Entity entity = Bukkit.getEntity(selectedId);
			if (entity != null) {
				sendGlowPacket(player, entity, false, Color.WHITE);
			}
		}
	}

	private boolean isSupportedDisplay(Entity entity) {
		return entity instanceof BlockDisplay || entity instanceof TextDisplay || entity instanceof ItemDisplay;
	}

	private boolean isOnCooldown(Player player) {
		long now = System.currentTimeMillis();
		Long last = lastWandUse.get(player.getUniqueId());
		if (last != null && now - last < WAND_COOLDOWN_MS) {
			return true;
		}
		lastWandUse.put(player.getUniqueId(), now);
		return false;
	}

	private boolean isInActiveChain(Player player, Entity entity) {
		if (entity == null) {
			return false;
		}

		UUID entityId = entity.getUniqueId();
		UUID selectedId = selectedEntities.get(player.getUniqueId());

		if (selectedId != null && selectedId.equals(entityId)) {
			return true;
		}

		List<UUID> queue = pendingPassengers.get(player.getUniqueId());
		return queue != null && queue.contains(entityId);
	}

	/**
	 * Everything connected to a display, for the follow-up list: what it rides (and what that rides, up to the base),
	 * then what rides it. Nathan: the whole stack should be offered, not only the passengers.
	 */
	private List<Entity> getConnected(Entity entity) {
		List<Entity> result = new ArrayList<>();
		for (Entity v = entity.getVehicle(); v != null; v = v.getVehicle()) {
			if (isSupportedDisplay(v)) {
				result.add(v);
			}
		}
		result.addAll(getSupportedPassengers(entity));
		return result;
	}

	// per player: displays already handled in this run, so a vehicle's passengers don't bring the selection back
	private final Map<UUID, Set<UUID>> handled = new HashMap<>();

	private List<Entity> getSupportedPassengers(Entity entity) {
		List<Entity> result = new ArrayList<>();
		for (Entity passenger : entity.getPassengers()) {
			if (isSupportedDisplay(passenger)) {
				result.add(passenger);
			}
		}
		return result;
	}

	private UUID getCurrentPendingId(Player player) {
		List<UUID> queue = pendingPassengers.get(player.getUniqueId());
		if (queue == null || queue.isEmpty()) {
			return null;
		}
		return queue.get(0);
	}

	private Entity getCurrentPendingEntity(Player player) {
		UUID id = getCurrentPendingId(player);
		return id == null ? null : Bukkit.getEntity(id);
	}

	private void glowCurrentPending(Player player) {
		Entity pending = getCurrentPendingEntity(player);
		if (pending != null) {
			sendGlowPacket(player, pending, true, YELLOW_GLOW);
		}
	}

	private void clearPending(Player player) {
		UUID playerId = player.getUniqueId();

		List<UUID> queue = pendingPassengers.remove(playerId);
		selectedDist.remove(playerId);
		handled.remove(playerId);

		if (queue != null) {
			for (UUID id : queue) {
				Entity entity = Bukkit.getEntity(id);
				if (entity != null) {
					sendGlowPacket(player, entity, false, Color.WHITE);
				}
			}
		}
	}

	private void setPendingList(Player player, List<Entity> passengers, float dist) {
		clearPending(player);

		if (passengers == null || passengers.isEmpty()) {
			return;
		}

		List<UUID> ids = new ArrayList<>();
		for (Entity passenger : passengers) {
			if (passenger != null && isSupportedDisplay(passenger)) {
				ids.add(passenger.getUniqueId());
			}
		}

		if (ids.isEmpty()) {
			return;
		}

		pendingPassengers.put(player.getUniqueId(), ids);
		selectedDist.put(player.getUniqueId(), dist);
		Set<UUID> done = new HashSet<>();
		UUID sel = selectedEntities.get(player.getUniqueId());
		if (sel != null) {
			done.add(sel);
		}
		handled.put(player.getUniqueId(), done);
		glowCurrentPending(player);
	}

	private void updatePendingDistance(Player player, float dist) {
		if (pendingPassengers.containsKey(player.getUniqueId())) {
			selectedDist.put(player.getUniqueId(), dist);
		}
	}

	private void advancePendingQueue(Player player) {
		List<UUID> queue = pendingPassengers.get(player.getUniqueId());
		if (queue == null || queue.isEmpty()) {
			return;
		}

		UUID oldId = queue.remove(0);
		handled.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(oldId);
		Entity oldEntity = Bukkit.getEntity(oldId);
		if (oldEntity != null) {
			sendGlowPacket(player, oldEntity, false, Color.WHITE);
		}

		if (queue.isEmpty()) {
			pendingPassengers.remove(player.getUniqueId());
		} else {
			glowCurrentPending(player);
		}
	}

	private void prependPassengers(Player player, List<Entity> passengers) {
		if (passengers == null || passengers.isEmpty()) {
			return;
		}

		UUID playerId = player.getUniqueId();
		List<UUID> queue = pendingPassengers.computeIfAbsent(playerId, k -> new ArrayList<>());

		int insertIndex = 0;
		for (Entity passenger : passengers) {
			if (passenger != null && isSupportedDisplay(passenger)) {
				UUID id = passenger.getUniqueId();
				Set<UUID> done = handled.get(playerId);
				if (!queue.contains(id) && (done == null || !done.contains(id))) {
					queue.add(insertIndex++, id);
				}
			}
		}

		glowCurrentPending(player);
	}

	private void switchSelection(Player player, Entity newEntity, Color color) {
		UUID playerId = player.getUniqueId();
		UUID oldSelectedId = selectedEntities.get(playerId);

		if (newEntity == null) {
			selectedEntities.remove(playerId);
		} else {
			selectedEntities.put(playerId, newEntity.getUniqueId());
		}

		if (oldSelectedId != null && (newEntity == null || !oldSelectedId.equals(newEntity.getUniqueId()))) {
			Entity oldEntity = Bukkit.getEntity(oldSelectedId);
			if (oldEntity != null) {
				sendGlowPacket(player, oldEntity, false, Color.WHITE);
			}
		}

		if (newEntity != null) {
			sendGlowPacket(player, newEntity, true, color);
		}
	}

	@SuppressWarnings("deprecation")
	private void sendConnectedEntityOptions(Player player) {
		int count = 0;
		List<UUID> queue = pendingPassengers.get(player.getUniqueId());
		if (queue != null) {
			count = queue.size();
		}

		player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Connected Display Entities Found");
		player.sendMessage(ChatColor.YELLOW + "Pending passengers: " + ChatColor.AQUA + count);

		TextComponent prefix = new TextComponent(ChatColor.GRAY + "[");

		TextComponent confirm = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "Apply Same Settings");
		confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity confirm"));
		confirm.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.GREEN + "Apply the same distance to the yellow passenger").create()));

		TextComponent sep1 = new TextComponent(ChatColor.GRAY + " | ");

		TextComponent change = new TextComponent(ChatColor.YELLOW + "" + ChatColor.BOLD + "Manual");
		change.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity change"));
		change.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.YELLOW + "Manually edit this passenger, then click Finish Current")
						.create()));

		TextComponent sep2 = new TextComponent(ChatColor.GRAY + " | ");

		TextComponent deny = new TextComponent(ChatColor.RED + "" + ChatColor.BOLD + "Skip");
		deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity deny"));
		deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.RED + "Skip only this yellow passenger and move to the next queued one")
						.create()));

		TextComponent suffix = new TextComponent(ChatColor.GRAY + "]");

		TextComponent line = new TextComponent();
		line.addExtra(prefix);
		line.addExtra(confirm);
		line.addExtra(sep1);
		line.addExtra(change);
		line.addExtra(sep2);
		line.addExtra(deny);
		line.addExtra(suffix);

		player.spigot().sendMessage(line);
	}

	@SuppressWarnings("deprecation")
	private void sendFinishCurrentOptions(Player player) {
		int count = 0;
		List<UUID> queue = pendingPassengers.get(player.getUniqueId());
		if (queue != null) {
			count = queue.size();
		}

		player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Manual Editing Mode");
		player.sendMessage(ChatColor.YELLOW + "Finish the current green display when ready. Remaining queue: "
				+ ChatColor.AQUA + count);

		TextComponent finishCurrent = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "[Finish Current]");
		finishCurrent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity next"));
		finishCurrent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.GREEN + "Move to the next queued display, or stop if none remain")
						.create()));

		TextComponent spacer = new TextComponent(" ");

		TextComponent stop = new TextComponent(ChatColor.RED + "" + ChatColor.BOLD + "[Stop]");
		stop.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity cancel"));
		stop.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.RED + "Stop editing and clear all glow").create()));

		TextComponent line = new TextComponent();
		line.addExtra(finishCurrent);
		line.addExtra(spacer);
		line.addExtra(stop);

		player.spigot().sendMessage(line);
	}

	@SuppressWarnings("deprecation")
	private void finishEditingMessage(Player player) {
		TextComponent finish = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "Finish Editing");
		finish.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity cancel"));
		finish.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
				new ComponentBuilder(ChatColor.GREEN + "Unselect everything and stop glowing").create()));

		player.sendMessage(ChatColor.YELLOW + "No more connected passengers. Click when finished editing.");
		TextComponent tc = new TextComponent();
		tc.addExtra(finish);
		player.spigot().sendMessage(tc);
	}

	private void processNextManual(Player player) {
		Entity pendingEntity = getCurrentPendingEntity(player);

		if (pendingEntity == null) {
			manualMode.remove(player.getUniqueId());
			clearPending(player);
			switchSelection(player, null, GREEN_GLOW);
			player.sendMessage(ChatColor.YELLOW + "Manual editing complete.");
			return;
		}

		List<Entity> nextPassengers = getConnected(pendingEntity);

		advancePendingQueue(player);
		switchSelection(player, pendingEntity, GREEN_GLOW);

		if (!nextPassengers.isEmpty()) {
			prependPassengers(player, nextPassengers);
		}

		player.sendMessage(ChatColor.GREEN + "Switched to next queued connected entity.");

		if (getCurrentPendingId(player) != null) {
			sendFinishCurrentOptions(player);
		} else {
			manualMode.remove(player.getUniqueId());
			finishEditingMessage(player);
		}
	}

	public static boolean debugEnabled = false;
	public static float rangeExtra = 0.005f;

	/** A newly placed item display missing its tint (Axiom drops it) takes it from the item a nearby player holds. */
	@EventHandler
	public void onDisplaySpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
		if (event.getEntity() instanceof ItemDisplay d) {
			if (getConfig().getBoolean("debug-display-spawns", false)) {
				getLogger().info("[spawn] " + d.getUniqueId() + " at spawn: " + d.getItemStack());
				Bukkit.getScheduler().runTaskLater(this, () -> getLogger().info("[spawn] " + d.getUniqueId()
						+ " 5 ticks later: " + d.getItemStack()), 5);
			}
			Bukkit.getScheduler().runTask(this, () -> {
				if (d.isValid()) {
					colorSlider.copyHeldTint(d);
				}
			});
		}
	}

	@EventHandler
	public void onPlayerLeftClick(PlayerInteractEvent event) {
		Player player = event.getPlayer();
		ItemStack item = player.getInventory().getItemInMainHand();

		if (!isEditStick(item)) {
			return;
		}

		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_AIR
				&& action != Action.LEFT_CLICK_BLOCK) {
			return;
		}

		if (isOnCooldown(player)) {
			event.setCancelled(true);
			return;
		}

		if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
			Entity closestLookEntity = null;
			double bestDot = -1.0;

			Vector lookDir = player.getEyeLocation().getDirection().normalize();
			Location eyeLoc = player.getEyeLocation();

			for (Entity e : player.getNearbyEntities(10, 10, 10)) {
				if (e == player) {
					continue;
				}
				if (!isSupportedDisplay(e)) {
					continue;
				}

				Vector toEntity = e.getLocation().toVector().add(new Vector(0, e.getHeight() * 0.5, 0))
						.subtract(eyeLoc.toVector());

				if (toEntity.lengthSquared() == 0) {
					continue;
				}

				toEntity.normalize();
				double dot = lookDir.dot(toEntity);

				if (dot > bestDot) {
					bestDot = dot;
					closestLookEntity = e;
				}
			}

			if (closestLookEntity == null) {
				return;
			}

			boolean keepChain = isInActiveChain(player, closestLookEntity);

			if (!keepChain) {
				clearPending(player);
				manualMode.remove(player.getUniqueId());
			}

			switchSelection(player, closestLookEntity, GREEN_GLOW);

			player.sendMessage(
					ChatColor.GREEN + "Selected entity: " + ChatColor.YELLOW + closestLookEntity.getType().name()
							+ ChatColor.GRAY + " [" + closestLookEntity.getUniqueId() + "]");
			if (!keepChain && !getConnected(closestLookEntity).isEmpty()) {
				TextComponent skip = new TextComponent(ChatColor.GRAY + "[" + ChatColor.RED + ChatColor.BOLD + "Skip this one"
						+ ChatColor.GRAY + "]");
				skip.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tsentity skip"));
				skip.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(ChatColor.RED
						+ "Leave this display's range as it is and start with the next one in its stack").create()));
				TextComponent line = new TextComponent(ChatColor.YELLOW + "Connected displays: "
						+ getConnected(closestLookEntity).size() + " ");
				line.addExtra(skip);
				player.spigot().sendMessage(line);
			}
			if (closestLookEntity instanceof Display sel) {
				player.sendMessage(ChatColor.GRAY + "Culling box: " + sel.getDisplayWidth() + " x "
						+ sel.getDisplayHeight() + ", view range " + sel.getViewRange()
						+ ChatColor.DARK_GRAY + " (/tsentity fit sizes it and its passengers)");
			}

			if (keepChain) {
				if (manualMode.contains(player.getUniqueId())) {
					sendFinishCurrentOptions(player);
				} else if (getCurrentPendingId(player) != null) {
					sendConnectedEntityOptions(player);
				}
			}

			event.setCancelled(true);
			return;
		}

		UUID selectedId = selectedEntities.get(player.getUniqueId());
		if (selectedId == null) {
			player.sendMessage(ChatColor.RED + "You must select a display entity first!");
			event.setCancelled(true);
			return;
		}

		Entity selected = Bukkit.getEntity(selectedId);
		if (selected == null) {
			selectedEntities.remove(player.getUniqueId());
			clearPending(player);
			manualMode.remove(player.getUniqueId());
			player.sendMessage(ChatColor.RED + "Selected entity no longer exists.");
			event.setCancelled(true);
			return;
		}

		if (!(selected instanceof Display display)) {
			player.sendMessage(ChatColor.RED + "Selected entity is not a display entity.");
			event.setCancelled(true);
			return;
		}

		Location clickedLocation = player.getEyeLocation();
		Location diff = selected.getLocation().subtract(clickedLocation);

		double distance = Math.sqrt(diff.getX() * diff.getX() + diff.getY() * diff.getY() + diff.getZ() * diff.getZ());
		float viewRange = (float) (distance + rangeExtra) / 64.0f;
		if (debugEnabled) {
			player.sendMessage(ChatColor.AQUA + "--------DEBUG--------");
			player.sendMessage(ChatColor.AQUA + "Distance Using Formula: " + distance);
			player.sendMessage(ChatColor.AQUA + "Distance using whole: "
					+ (Math.abs(diff.getX()) + Math.abs(diff.getY()) + Math.abs(diff.getZ())));
			player.sendMessage(ChatColor.AQUA + "Entity Location: " + selected.getLocation().toString());
			player.sendMessage(ChatColor.AQUA + "Player Location: " + clickedLocation.toString());
			player.sendMessage(ChatColor.AQUA + "---------------------");
		}
		display.setViewRange(viewRange);

		player.sendMessage(ChatColor.GREEN + "Set display view range to " + ChatColor.YELLOW + viewRange);
		event.setCancelled(true);

		// In manual mode, do not rebuild the queue. Just keep editing and show the
		// finish button.
		if (manualMode.contains(player.getUniqueId())) {
			updatePendingDistance(player, viewRange);
			sendFinishCurrentOptions(player);
			return;
		}

		// If there is already a queue, keep it and only update the stored distance.
		if (getCurrentPendingId(player) != null) {
			updatePendingDistance(player, viewRange);
			sendConnectedEntityOptions(player);
			return;
		}

		List<Entity> passengers = getConnected(selected);
		if (!passengers.isEmpty()) {
			setPendingList(player, passengers, viewRange);
			sendConnectedEntityOptions(player);
		} else {
			clearPending(player);
			finishEditingMessage(player);
		}
	}

	private boolean isEditStick(ItemStack item) {
		if (item == null || item.getType().isAir()) {
			return false;
		}

		if (!item.getType().name().equalsIgnoreCase("STICK")) {
			return false;
		}

		if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
			return false;
		}

		String expected = ChatColor.translateAlternateColorCodes('&', "&8[&2&lRange Modifier&8]");
		String actual = item.getItemMeta().getDisplayName();
		return expected.equals(actual);
	}

	private void sendGlowPacket(Player viewer, Entity entity, boolean glowing, Color c) {
		if (entity instanceof Display display) {
			display.setGlowing(glowing ? true : false);
			display.setGlowColorOverride(glowing ? c : null);
		}

	}

	/**
	 * Skip the selected display before setting any range (Nathan): it keeps its range, and the next display in its
	 * stack becomes the green selection, the rest queued yellow. Left-clicking then sets the range for that one and
	 * the usual Apply / Manual / Skip carries on through the rest.
	 */
	private void skipSelected(Player player) {
		UUID selectedId = selectedEntities.get(player.getUniqueId());
		Entity selected = selectedId == null ? null : Bukkit.getEntity(selectedId);
		if (selected == null) {
			player.sendMessage(ChatColor.RED + "Select a display with the wand first.");
			return;
		}
		List<Entity> rest = getConnected(selected);
		manualMode.remove(player.getUniqueId());
		clearPending(player);
		if (rest.isEmpty()) {
			switchSelection(player, null, GREEN_GLOW);
			player.sendMessage(ChatColor.YELLOW + "Skipped. Nothing is connected to it, so there's nothing next.");
			return;
		}
		Entity first = rest.remove(0);
		switchSelection(player, first, GREEN_GLOW);
		Set<UUID> done = new HashSet<>(List.of(selected.getUniqueId(), first.getUniqueId()));
		handled.put(player.getUniqueId(), done);
		List<UUID> ids = new ArrayList<>();
		for (Entity e : rest) {
			ids.add(e.getUniqueId());
		}
		if (!ids.isEmpty()) {
			pendingPassengers.put(player.getUniqueId(), ids);
		}
		prependPassengers(player, getConnected(first));
		glowCurrentPending(player);
		player.sendMessage(ChatColor.YELLOW + "Skipped. Now on " + ChatColor.GREEN + first.getType().name()
				+ ChatColor.YELLOW + ": left-click to set its range" + (getCurrentPendingId(player) != null
						? ", then Apply / Manual / Skip goes on through the rest." : "."));
	}

	/** Fit the culling box of the wand's selection's whole stack: from the display at its base up through every rider. */
	private void fitSelection(Player player) {
		UUID selectedId = selectedEntities.get(player.getUniqueId());
		Entity selected = selectedId == null ? null : Bukkit.getEntity(selectedId);
		if (selected == null) {
			player.sendMessage(ChatColor.RED + "Select a display with the wand first (/tsentity wand, right click).");
			return;
		}
		int fitted = 0, unknown = 0;
		Entity base = selected;
		while (base.getVehicle() != null && isSupportedDisplay(base.getVehicle())) {
			base = base.getVehicle();
		}
		List<Entity> todo = new ArrayList<>(List.of(base));
		while (!todo.isEmpty()) {
			Entity e = todo.remove(0);
			todo.addAll(getSupportedPassengers(e));
			if (!(e instanceof ItemDisplay d)) {
				continue;
			}
			Culling.Fit f = culling.apply(d);
			if (f == null) {
				unknown++;
				continue;
			}
			fitted++;
			player.sendMessage(ChatColor.GREEN + f.model() + ": " + ChatColor.YELLOW + String.format(Locale.ROOT,
					"%.4f x %.4f", f.width(), f.height()));
		}
		player.sendMessage(ChatColor.GREEN + "Fitted " + fitted + " display(s)"
				+ (unknown > 0 ? ChatColor.GRAY + ", " + unknown + " with no model in the packs" : "") + ".");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("tintclipboard")) {
			if (sender instanceof Player p) {
				p.getInventory().addItem(tintClipboard.item());
				p.sendMessage(ChatColor.AQUA + "Paint Brush: sneak + right-click a tinted prop to pick up its colours, right-click another to paint them on.");
			} else {
				sender.sendMessage("In game only.");
			}
			return true;
		}
		if (command.getName().equalsIgnoreCase("colorslider")) {
			if (sender instanceof Player p) {
				colorSlider.open(p);
			} else {
				sender.sendMessage("In game only.");
			}
			return true;
		}
		if (!command.getName().equalsIgnoreCase("tsentity")) {
			return false;
		}

		if (args.length > 0 && !args[0].equalsIgnoreCase("fit")
				&& Culling.SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
			return culling.onCommand(sender, args);
		}

		if (!(sender instanceof Player player)) {
			sender.sendMessage(ChatColor.RED + "Only players can use this command.");
			return true;
		}

		if (args.length > 0 && args[0].equalsIgnoreCase("fit")) {
			fitSelection(player);
			return true;
		}

		if (args.length > 0 && args[0].equalsIgnoreCase("frames")) {
			frames.command(player, args);
			return true;
		}

		if (args.length > 0 && args[0].equalsIgnoreCase("variant")) {
			UUID sel = selectedEntities.get(player.getUniqueId());
			variants.command(player, args, sel == null ? null : Bukkit.getEntity(sel));
			return true;
		}

		if (args.length > 0 && args[0].equalsIgnoreCase("cullrange")) {
			cullRange.command(player, args);
			return true;
		}

		if (args.length > 0 && args[0].equalsIgnoreCase("skip")) {
			skipSelected(player);
			return true;
		}

		if (args.length == 0) {
			player.sendMessage(ChatColor.YELLOW + "/tsentity wand");
			player.sendMessage(ChatColor.YELLOW + "/tsentity cancel");
			player.sendMessage(ChatColor.YELLOW + "/tsentity confirm");
			player.sendMessage(ChatColor.YELLOW + "/tsentity change");
			player.sendMessage(ChatColor.YELLOW + "/tsentity deny");
			player.sendMessage(ChatColor.YELLOW + "/tsentity next");
			player.sendMessage(ChatColor.YELLOW + "/tsentity debug enable/disable");
			player.sendMessage(ChatColor.YELLOW + "/tsentity skip " + ChatColor.GRAY + "- skip the selected one, start on the next in its stack");
			player.sendMessage(ChatColor.GOLD + "Converting:");
			player.sendMessage(ChatColor.YELLOW + "/tsentity frames [radius] [confirm] " + ChatColor.GRAY + "- item frames into matching item displays");
			player.sendMessage(ChatColor.YELLOW + "/tsentity frames undo [radius] " + ChatColor.GRAY + "- put converted frames back");
			player.sendMessage(ChatColor.YELLOW + "/tsentity variant " + ChatColor.GRAY + "- teach old -> remade model pairs, then convert old ones");
			player.sendMessage(ChatColor.GOLD + "View range:");
			player.sendMessage(ChatColor.YELLOW + "/tsentity cullrange [radius] [confirm] " + ChatColor.GRAY + "- trace how far each display can be seen from and lower its range to that (only ranges of 1 or below, only ever lower)");
			player.sendMessage(ChatColor.YELLOW + "/tsentity cullrange undo " + ChatColor.GRAY + "- put the last ones back");
			player.sendMessage(ChatColor.GOLD + "Culling:");
			player.sendMessage(ChatColor.YELLOW + "/tsentity fit " + ChatColor.GRAY + "- the wand's selection and its passengers");
			player.sendMessage(ChatColor.YELLOW + "/tsentity look [shift] [center] " + ChatColor.GRAY + "- the display you look at");
			player.sendMessage(ChatColor.YELLOW + "/tsentity near [radius] [shift] [center]");
			player.sendMessage(ChatColor.YELLOW + "/tsentity center | shift " + ChatColor.GRAY + "- move the one you look at");
			player.sendMessage(ChatColor.YELLOW + "/tsentity ignore <add|remove|list>");
			player.sendMessage(ChatColor.YELLOW + "/tsentity report | reload");
			return true;
		}

		if (args[0].equalsIgnoreCase("debug")) {
			if (args.length != 2) {
				player.sendMessage(ChatColor.YELLOW + "/tsentity debug enable/disable");
			} else {
				if (args[1].equalsIgnoreCase("enable")) {
					debugEnabled = true;
					player.sendMessage(ChatColor.YELLOW + "Debug Enabled");
				} else if (args[1].equalsIgnoreCase("disable")) {
					debugEnabled = false;
					player.sendMessage(ChatColor.YELLOW + "Debug Disabled");
				}
			}
			return true;
		}

		if (args[0].equalsIgnoreCase("wand")) {
			ItemStack wand = new ItemStack(org.bukkit.Material.STICK);
			org.bukkit.inventory.meta.ItemMeta meta = wand.getItemMeta();

			if (meta != null) {
				meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&8[&2&lRange Modifier&8]"));
				wand.setItemMeta(meta);
			}

			player.getInventory().addItem(wand);
			player.sendMessage(ChatColor.GREEN + "You received the edit wand.");
			return true;
		}

		if (args[0].equalsIgnoreCase("cancel")) {
			manualMode.remove(player.getUniqueId());
			clearPending(player);
			switchSelection(player, null, GREEN_GLOW);
			player.sendMessage(ChatColor.YELLOW + "Selection cleared.");
			return true;
		}

		if (args[0].equalsIgnoreCase("deny")) {
			manualMode.remove(player.getUniqueId());

			Entity pendingEntity = getCurrentPendingEntity(player);

			if (pendingEntity == null) {
				player.sendMessage(ChatColor.RED + "There is no pending connected entity change.");
				return true;
			}

			// Skip only the current yellow entry. Do NOT add its passengers.
			advancePendingQueue(player);

			if (getCurrentPendingId(player) != null) {
				sendConnectedEntityOptions(player);
			} else {
				finishEditingMessage(player);
			}

			player.sendMessage(ChatColor.YELLOW + "Skipped connected entity.");
			return true;
		}
		if (args[0].equalsIgnoreCase("offset")) {
			if (args.length == 2) {
				rangeExtra = Float.parseFloat(args[1]);
				player.sendMessage(ChatColor.YELLOW + "Extra Range set to " + rangeExtra + "!");
			} else if (args.length == 1) {
				player.sendMessage(ChatColor.YELLOW + "Current Offset is " + rangeExtra + "!");
			} else {
				player.sendMessage(ChatColor.RED + "Provided too many Params!");
			}
			return true;
		}

		if (args[0].equalsIgnoreCase("confirm")) {
			manualMode.remove(player.getUniqueId());

			Entity pendingEntity = getCurrentPendingEntity(player);
			Float pendingDist = selectedDist.get(player.getUniqueId());

			if (pendingEntity == null || pendingDist == null) {
				player.sendMessage(ChatColor.RED + "There is no pending connected entity change.");
				return true;
			}

			if (!(pendingEntity instanceof Display pendingDisplay)) {
				advancePendingQueue(player);
				player.sendMessage(ChatColor.RED + "The connected entity is not a display entity.");
				if (getCurrentPendingId(player) != null) {
					sendConnectedEntityOptions(player);
				} else {
					finishEditingMessage(player);
				}
				return true;
			}

			List<Entity> nextPassengers = getConnected(pendingEntity);

			advancePendingQueue(player);
			pendingDisplay.setViewRange(pendingDist);
			switchSelection(player, pendingEntity, GREEN_GLOW);

			player.sendMessage(ChatColor.GREEN + "Applied view range " + ChatColor.YELLOW + pendingDist
					+ ChatColor.GREEN + " to the connected display entity.");

			if (!nextPassengers.isEmpty()) {
				prependPassengers(player, nextPassengers);
			}

			if (getCurrentPendingId(player) != null) {
				sendConnectedEntityOptions(player);
			} else {
				finishEditingMessage(player);
			}

			return true;
		}

		if (args[0].equalsIgnoreCase("change")) {
			Entity pendingEntity = getCurrentPendingEntity(player);

			if (pendingEntity == null) {
				player.sendMessage(ChatColor.RED + "There is no pending connected entity to switch to.");
				return true;
			}

			if (!isSupportedDisplay(pendingEntity)) {
				advancePendingQueue(player);
				player.sendMessage(ChatColor.RED + "The connected entity is not a display entity.");
				if (getCurrentPendingId(player) != null) {
					sendConnectedEntityOptions(player);
				} else {
					finishEditingMessage(player);
				}
				return true;
			}

			List<Entity> nextPassengers = getConnected(pendingEntity);

			advancePendingQueue(player);
			switchSelection(player, pendingEntity, GREEN_GLOW);

			if (!nextPassengers.isEmpty()) {
				prependPassengers(player, nextPassengers);
			}

			manualMode.add(player.getUniqueId());
			player.sendMessage(ChatColor.GREEN + "Now manually editing connected entity.");
			sendFinishCurrentOptions(player);
			return true;
		}

		if (args[0].equalsIgnoreCase("next")) {
			if (!manualMode.contains(player.getUniqueId())) {
				player.sendMessage(ChatColor.RED + "You are not currently in manual editing mode.");
				return true;
			}

			processNextManual(player);
			return true;
		}

		player.sendMessage(ChatColor.RED + "Unknown subcommand.");
		return true;
	}
}