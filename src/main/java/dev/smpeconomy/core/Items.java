package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Inventory helpers. Deliberately limited to the main inventory (slots 0-35), never armor or offhand, and avoids
 * {@code Inventory.add}, which deletes items that do not fit in creative mode. The name clashes with
 * {@code net.minecraft.world.item.Items}, so one of them must be fully qualified.
 */
public final class Items {
	public static final int MAIN_SIZE = 36;

	private Items() {}

	/** No custom components relative to the item default (name, enchantment, damage, contents). */
	public static boolean isPristine(ItemStack stack) {
		return !stack.isEmpty() && stack.getComponentsPatch().isEmpty();
	}

	/**
	 * Whether the stack holds other items or creatures (shulker or chest contents, bundle, loaded crossbow, bees, loot
	 * table, custom block or entity data). NPCs never buy these since the contents would be deleted too, and backpacks
	 * reject them to prevent nesting. Empty shulkers and bundles and spawn eggs with default data do not count.
	 */
	public static boolean holdsItems(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null && !Mc.containerItems(container).isEmpty()) return true;
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null && !bundle.isEmpty()) return true;
		ChargedProjectiles projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
		if (projectiles != null && !projectiles.isEmpty()) return true;
		Bees bees = stack.get(DataComponents.BEES);
		if (bees != null && !bees.bees().isEmpty()) return true;
		DataComponentPatch patch = stack.getComponentsPatch();
		for (DataComponentType<?> type : List.of(DataComponents.CONTAINER_LOOT, DataComponents.BLOCK_ENTITY_DATA, DataComponents.ENTITY_DATA)) {
			if (Mc.patchSets(patch, type)) return true; // differs from the default; spawn eggs have ENTITY_DATA by default
		}
		return false;
	}

	/**
	 * Gives a copy of {@code stack} (count may exceed the stack size) and drops the rest at the player's feet. If the
	 * player is dead or disconnected, everything is dropped like a vanilla container: items placed in a dead player's
	 * inventory are lost on respawn, and a disconnecting player has already been saved. For selftest players (
	 * {@link TestPlayers}) drops are removed immediately and only recorded ({@code TestContext.drops(player)}), so no real
	 * items land at spawn.
	 *
	 * @return number of dropped items
	 */
	public static int giveOrDrop(ServerPlayer player, ItemStack stack) {
		return giveOrDrop(player, stack, null);
	}

	public static int giveOrDrop(ServerPlayer player, ItemStack stack, Consumer<ItemEntity> onDrop) {
		if (stack.isEmpty()) return 0;
		ItemStack rest = stack.copy();
		if (canReceive(player)) insert(player.getInventory(), rest);
		int dropped = rest.getCount();
		int max = Math.max(1, rest.getMaxStackSize());
		while (!rest.isEmpty()) {
			ItemStack part = rest.split(max);
			ItemEntity entity = Mc.drop(player, part);
			if (entity == null) {
				SmpEconomy.LOG.error("[SMPEco] Could not drop {}x {} for {}", part.getCount(), id(part.getItem()), player.getPlainTextName());
				continue;
			}
			if (TestPlayers.isTestPlayer(player.getUUID())) TestPlayers.discardDrop(player.getUUID(), entity);
			if (onDrop != null) onDrop.accept(entity);
		}
		return dropped;
	}

	public static int giveOrDrop(ServerPlayer player, Item item, int count) {
		return giveOrDrop(player, new ItemStack(item, count));
	}

	/** Alive and connected, meaning inventory changes get saved. */
	public static boolean canReceive(ServerPlayer player) {
		return player.isAlive() && !player.hasDisconnected();
	}

	/**
	 * Creative (unlimited items, {@code abilities.instabuild}) or spectator. Items from such an inventory were not
	 * earned and must not be exchanged for money. One shared rule for all item-to-money paths; whether a feature's
	 * config allows it is up to the feature.
	 */
	public static boolean isCreativeOrSpectator(ServerPlayer player) {
		return player.hasInfiniteMaterials() || player.isSpectator();
	}

	public static int spaceFor(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return 0;
		Inventory inv = player.getInventory();
		int max = maxStack(inv, stack);
		long space = 0;
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack slot = inv.getItem(i);
			if (slot.isEmpty()) space += max;
			else if (ItemStack.isSameItemSameComponents(slot, stack)) space += Math.max(0, max - slot.getCount());
		}
		return (int) Math.min(Integer.MAX_VALUE, space);
	}

	/** The stack's own count is ignored. */
	public static int fitCount(ServerPlayer player, ItemStack stack) {
		return spaceFor(player, stack);
	}

	public static boolean canFit(ServerPlayer player, ItemStack stack) {
		return spaceFor(player, stack) >= stack.getCount();
	}

	/** Simulates on a copy of the main inventory. */
	public static boolean canFitAll(ServerPlayer player, List<ItemStack> stacks) {
		Inventory inv = player.getInventory();
		NonNullList<ItemStack> sim = NonNullList.withSize(MAIN_SIZE, ItemStack.EMPTY);
		for (int i = 0; i < MAIN_SIZE; i++) sim.set(i, inv.getItem(i).copy());
		for (ItemStack s : stacks) {
			ItemStack rest = s.copy();
			insertInto(sim, rest, maxStack(inv, rest));
			if (!rest.isEmpty()) return false;
		}
		return true;
	}

	public static int count(ServerPlayer player, Predicate<ItemStack> filter) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && filter.test(s)) total += s.getCount();
		}
		return total;
	}

	public static int countPristine(ServerPlayer player, Item item) {
		return count(player, s -> s.is(item) && isPristine(s));
	}

	/**
	 * Removes from the back to the front, so the hotbar goes last.
	 *
	 * @return number actually removed
	 */
	public static int remove(ServerPlayer player, Predicate<ItemStack> filter, int amount) {
		Inventory inv = player.getInventory();
		int left = amount;
		for (int i = MAIN_SIZE - 1; i >= 0 && left > 0; i--) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !filter.test(s)) continue;
			int take = Math.min(left, s.getCount());
			s.shrink(take);
			if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
			left -= take;
		}
		if (left != amount) inv.setChanged();
		return amount - left;
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	/** "diamond" or "minecraft:diamond". Empty if unknown, never air. */
	public static Optional<Item> byId(String id) {
		Identifier key = id == null ? null : Identifier.tryParse(id);
		// ITEM is a DefaultedRegistry: unknown ids would otherwise return air
		if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return Optional.empty();
		Item item = BuiltInRegistries.ITEM.getValue(key);
		return item == net.minecraft.world.item.Items.AIR ? Optional.empty() : Optional.of(item);
	}

	public static List<ItemStack> splitToStacks(ItemStack stack) {
		List<ItemStack> list = new ArrayList<>();
		ItemStack rest = stack.copy();
		int max = Math.max(1, rest.getMaxStackSize());
		while (!rest.isEmpty()) list.add(rest.split(max));
		return list;
	}

	/** Fills matching stacks first, then empty slots. {@code rest} shrinks by what was inserted. */
	private static void insert(Inventory inv, ItemStack rest) {
		int max = maxStack(inv, rest);
		boolean changed = false;
		for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
			for (int i = 0; i < MAIN_SIZE && !rest.isEmpty(); i++) {
				ItemStack slot = inv.getItem(i);
				if (pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < max) {
					int add = Math.min(max - slot.getCount(), rest.getCount());
					slot.grow(add);
					rest.shrink(add);
					changed = true;
				} else if (pass == 1 && slot.isEmpty()) {
					inv.setItem(i, rest.split(Math.min(max, rest.getCount())));
					changed = true;
				}
			}
		}
		if (changed) inv.setChanged();
	}

	private static void insertInto(NonNullList<ItemStack> slots, ItemStack rest, int max) {
		for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
			for (int i = 0; i < slots.size() && !rest.isEmpty(); i++) {
				ItemStack slot = slots.get(i);
				if (pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < max) {
					int add = Math.min(max - slot.getCount(), rest.getCount());
					slot.grow(add);
					rest.shrink(add);
				} else if (pass == 1 && slot.isEmpty()) {
					slots.set(i, rest.split(Math.min(max, rest.getCount())));
				}
			}
		}
	}

	private static int maxStack(Inventory inv, ItemStack stack) {
		return Math.max(1, inv.getMaxStackSize(stack));
	}
}
