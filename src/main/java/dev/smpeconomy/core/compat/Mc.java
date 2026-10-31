package dev.smpeconomy.core.compat;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;
//? if >=26.1 {
/*import net.minecraft.world.item.ItemStackTemplate;
*///?}

/**
 * Vanilla APIs whose meaning or shape changes in MC 26.x, not just their name. Feature code calls these helpers instead of
 * the vanilla method, so the version branches ({@code //? if}) stay in this class. Each method means the same on every
 * version (self test {@code core/compat-mc}) and is a plain pass-through on 1.21.11. Pure renames (entity type constants,
 * dye collections, entity tags, sgui elements) are handled in stonecutter.gradle.kts.
 */
public final class Mc {
	/** Same as vanilla {@code ItemInput.ERROR_STACK_TOO_BIG}, which is private. */
	private static final Dynamic2CommandExceptionType STACK_TOO_BIG = new Dynamic2CommandExceptionType(
		(item, max) -> Component.translatableEscape("arguments.item.overstacked", item, max));

	private Mc() {}

	/** 26.x removed {@code displayClientMessage} in favor of {@code sendOverlayMessage}. */
	public static void overlay(ServerPlayer player, Component component) {
		//? if >=26.1 {
		/*player.sendOverlayMessage(component);
		*///?} else {
		player.displayClientMessage(component, true);
		//?}
	}

	/** 26.2+ removed {@code getChar()}; {@code toString()} is "§" + code. */
	public static char formatChar(ChatFormatting format) {
		//? if >=26.2 {
		/*return format.toString().charAt(1);
		*///?} else {
		return format.getChar();
		//?}
	}

	/** Null for styles like bold. 26.2+ removed {@code getColor()} in favor of {@code TextColor}. */
	public static Integer formatRgb(ChatFormatting format) {
		//? if >=26.2 {
		/*net.minecraft.network.chat.TextColor color = net.minecraft.network.chat.TextColor.fromLegacyFormat(format);
		return color == null ? null : color.getValue();
		*///?} else {
		return format.getColor();
		//?}
	}

	/**
	 * Name of the item type, independent of stack contents. 26.x removed {@code getName()}; do not use
	 * {@code getName(ItemStack)}, where potions and arrows append their contents ("Uncraftable Potion" on the default stack).
	 */
	public static Component itemName(Item item) {
		//? if >=26.1 {
		/*return item.components().getOrDefault(net.minecraft.core.component.DataComponents.ITEM_NAME, net.minecraft.network.chat.CommonComponents.EMPTY);
		*///?} else {
		return item.getName();
		//?}
	}

	/** 26.x goes through {@code ItemStackTemplate}. */
	public static HoverEvent showItem(ItemStack stack) {
		//? if >=26.1 {
		/*return new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(stack));
		*///?} else {
		return new HoverEvent.ShowItem(stack);
		//?}
	}

	/** 26.x constructor takes a {@code UUID} first. */
	public static ServerBossEvent bossEvent(Component title, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
		//? if >=26.1 {
		/*return new ServerBossEvent(java.util.UUID.randomUUID(), title, color, overlay);
		*///?} else {
		return new ServerBossEvent(title, color, overlay);
		//?}
	}

	/**
	 * Time-of-day counter in ticks ({@code % 24000} is the clock), also for Nether and End, whose level data on 1.21.11
	 * is the overworld's. 26.x uses world clocks, here the overworld clock.
	 */
	public static long dayTime(Level level) {
		//? if >=26.1 {
		/*return level.getOverworldClockTime();
		*///?} else {
		return level.getDayTime();
		//?}
	}

	/** Counterpart of {@link #dayTime}. 26.x goes through the server's clock manager. */
	public static void setDayTime(ServerLevel level, long ticks) {
		//? if >=26.1 {
		/*level.registryAccess().get(net.minecraft.world.clock.WorldClocks.OVERWORLD)
			.ifPresent(clock -> level.getServer().clockManager().setTotalTicks(clock, ticks));
		*///?} else {
		level.setDayTime(ticks);
		//?}
	}

	/**
	 * Requires a FULL chunk (ticking not needed) with loaded entities; otherwise a saved display could still load in
	 * later.
	 */
	public static boolean canSpawnAt(ServerLevel level, double x, double z) {
		int cx = SectionPos.blockToSectionCoord(x), cz = SectionPos.blockToSectionCoord(z);
		return level.areEntitiesLoaded(chunkKey(cx, cz)) && level.getChunkSource().hasChunk(cx, cz);
	}

	/** For {@code areEntitiesLoaded} and similar. 26.x: {@code ChunkPos.pack(x, z)}. */
	public static long chunkKey(int chunkX, int chunkZ) {
		//? if >=26.1 {
		/*return ChunkPos.pack(chunkX, chunkZ);
		*///?} else {
		return ChunkPos.asLong(chunkX, chunkZ);
		//?}
	}

	/** 26.x: {@code pos.pack()}. */
	public static long chunkKey(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.pack();
		*///?} else {
		return pos.toLong();
		//?}
	}

	/** 26.x: {@code ChunkPos} is a record. */
	public static int chunkX(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.x();
		*///?} else {
		return pos.x;
		//?}
	}

	/** 26.x: {@code ChunkPos} is a record. */
	public static int chunkZ(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.z();
		*///?} else {
		return pos.z;
		//?}
	}

	/**
	 * False without a dragon fight. The fight class is renamed in 26.x with the same method; Stonecutter swaps the
	 * class name.
	 */
	public static boolean dragonPreviouslyKilled(ServerLevel end) {
		EndDragonFight fight = end == null ? null : end.getDragonFight();
		return fight != null && fight.hasPreviouslyKilledDragon();
	}

	public static boolean hasDragonFight(ServerLevel end) {
		return end != null && end.getDragonFight() != null;
	}

	/**
	 * Tag from a config reference ({@code "#minecraft:dirt"} in tasks.json, jobs.json, etc). Configs use 1.21.11 tag
	 * meanings. 26.x shrank block tag {@code minecraft:dirt} to dirt, coarse dirt and rooted dirt, and grass, podzol,
	 * mycelium, moss and mud moved to {@code minecraft:substrate_overworld}, which equals the old {@code #dirt}. So the
	 * block tag is redirected there, and "dig 64 dirt" still counts grass after a version update. Other tags and
	 * registries are unchanged.
	 */
	public static <T> TagKey<T> configTag(ResourceKey<? extends Registry<T>> registry, Identifier id) {
		//? if >=26.1 {
		/*if (registry.equals(Registries.BLOCK) && id.equals(Identifier.withDefaultNamespace("dirt"))) {
			id = Identifier.withDefaultNamespace("substrate_overworld");
		}
		*///?}
		return TagKey.create(registry, id);
	}

	/**
	 * Members of a config tag reference with 1.21.11 meaning, for any registry. Item tag {@code minecraft:dirt} also
	 * shrank on 26.x but has no {@code substrate_overworld} counterpart: the old {@code #dirt} is the union of
	 * {@code dirt}, {@code grass_blocks}, {@code moss_blocks} and {@code mud} there (the same ten items).
	 */
	public static <T> List<Holder<T>> configTagMembers(Registry<T> registry, Identifier id) {
		List<Holder<T>> members = new ArrayList<>();
		//? if >=26.1 {
		/*if (registry.key().equals(Registries.ITEM) && id.equals(Identifier.withDefaultNamespace("dirt"))) {
			for (String part : List.of("dirt", "grass_blocks", "moss_blocks", "mud")) {
				registry.getTagOrEmpty(TagKey.create(registry.key(), Identifier.withDefaultNamespace(part))).forEach(members::add);
			}
			return members;
		}
		*///?}
		registry.getTagOrEmpty(configTag(registry.key(), id)).forEach(members::add);
		return members;
	}

	/** 26.x removed {@code EntityType.is(TagKey)}; same check through the registry holder. */
	public static boolean is(EntityType<?> type, TagKey<EntityType<?>> tag) {
		//? if >=26.1 {
		/*return type.builtInRegistryHolder().is(tag);
		*///?} else {
		return type.is(tag);
		//?}
	}

	/** 26.3 replaced {@code setInvulnerable} with {@code setPermanentlyInvulnerable}. */
	public static void setInvulnerable(Entity entity) {
		//? if >=26.3 {
		/*entity.setPermanentlyInvulnerable(true);
		*///?} else {
		entity.setInvulnerable(true);
		//?}
	}

	/**
	 * Loads an entity with passengers from NBT with spawn reason {@code reason}. 26.2+ takes the reason as an
	 * {@code EntitySpawnRequest} (without {@code ignoreChecks}); it passes through the same load end where
	 * {@code EntityTypeOriginMixin} tags.
	 */
	public static Entity loadEntityRecursive(CompoundTag tag, Level level, EntitySpawnReason reason, EntityProcessor processor) {
		//? if >=26.2 {
		/*return EntityType.loadEntityRecursive(tag, level, new net.minecraft.world.entity.EntitySpawnRequest(reason, false), processor);
		*///?} else {
		return EntityType.loadEntityRecursive(tag, level, reason, processor);
		//?}
	}

	/**
	 * Drops a stack from the server in front of the player, with no owner binding and not randomly thrown. Returns the
	 * ItemEntity or null. 26.3: the second parameter is a {@code Prediction}; server-triggered means
	 * {@code SERVER_ONLY}, as vanilla does for rewards that do not fit.
	 */
	public static ItemEntity drop(ServerPlayer player, ItemStack stack) {
		//? if >=26.3 {
		/*return player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
		*///?} else {
		return player.drop(stack, false);
		//?}
	}

	/** 26.x: {@code assemble(input)} without registries. */
	public static <I extends RecipeInput> ItemStack assemble(Recipe<I> recipe, I input, HolderLookup.Provider registries) {
		//? if >=26.1 {
		/*return recipe.assemble(input);
		*///?} else {
		return recipe.assemble(input, registries);
		//?}
	}

	/** Bucket, bottle etc, otherwise empty. 26.x: nullable {@code ItemStackTemplate}, then {@code create()}. */
	public static ItemStack craftingRemainder(Item item) {
		//? if >=26.1 {
		/*ItemStackTemplate remainder = item.getCraftingRemainder();
		return remainder == null ? ItemStack.EMPTY : remainder.create();
		*///?} else {
		return item.getCraftingRemainder();
		//?}
	}

	/** 26.x: {@code typeHolder()}. */
	public static Holder<Item> itemHolder(ItemStack stack) {
		//? if >=26.1 {
		/*return stack.typeHolder();
		*///?} else {
		return stack.getItemHolder();
		//?}
	}

	/**
	 * Read only: on 1.21.11 the stored stacks themselves, on 26.x copies since contents are {@code ItemStackTemplate}s.
	 */
	public static List<ItemStack> containerItems(ItemContainerContents contents) {
		List<ItemStack> list = new ArrayList<>();
		//? if >=26.1 {
		/*for (ItemStackTemplate template : contents.nonEmptyItems()) list.add(template.create());
		*///?} else {
		contents.nonEmptyItems().forEach(list::add);
		//?}
		return list;
	}

	/** Read only, see {@link #containerItems}. 26.x: templates become copies. */
	public static List<ItemStack> bundleItems(BundleContents bundle) {
		List<ItemStack> list = new ArrayList<>();
		//? if >=26.1 {
		/*for (ItemStackTemplate template : bundle.items()) list.add(template.create());
		*///?} else {
		bundle.items().forEach(list::add);
		//?}
		return list;
	}

	/** 26.x constructor takes {@code ItemStackTemplate}s. */
	public static BundleContents bundle(List<ItemStack> stacks) {
		//? if >=26.1 {
		/*List<ItemStackTemplate> templates = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (!stack.isEmpty()) templates.add(ItemStackTemplate.fromNonEmptyStack(stack));
		}
		return new BundleContents(templates);
		*///?} else {
		return new BundleContents(stacks);
		//?}
	}

	/**
	 * Type to true (set) or false (default component removed). Order is unspecified; callers only test membership. 26.3
	 * replaced {@code entrySet()} with {@code split()}.
	 */
	public static Map<DataComponentType<?>, Boolean> patchChanges(DataComponentPatch patch) {
		Map<DataComponentType<?>, Boolean> out = new LinkedHashMap<>();
		//? if >=26.3 {
		/*DataComponentPatch.SplitResult split = patch.split();
		for (DataComponentType<?> type : split.added().keySet()) out.put(type, true);
		for (DataComponentType<?> type : split.removed()) out.put(type, false);
		*///?} else {
		for (Map.Entry<DataComponentType<?>, Optional<?>> e : patch.entrySet()) out.put(e.getKey(), e.getValue().isPresent());
		//?}
		return out;
	}

	/** True only if set to a value, not if unchanged or removed. 26.x removed {@code get(type)}. */
	public static boolean patchSets(DataComponentPatch patch, DataComponentType<?> type) {
		//? if >=26.1 {
		/*return Boolean.TRUE.equals(patchChanges(patch).get(type));
		*///?} else {
		Optional<?> v = patch.get(type);
		return v != null && v.isPresent();
		//?}
	}

	/** 26.x: {@code ItemInput} is a record. */
	public static Item inputItem(ItemInput input) {
		//? if >=26.1 {
		/*return input.item().value();
		*///?} else {
		return input.getItem();
		//?}
	}

	/**
	 * {@code checkSize}: exceeding the max stack size is a command error; otherwise overstacking is allowed. 26.x
	 * {@code createItemStack(count)} has no switch, so the limit is checked here with vanilla's error and the count is
	 * set after creating a single item.
	 */
	public static ItemStack inputStack(ItemInput input, int count, boolean checkSize) throws CommandSyntaxException {
		//? if >=26.1 {
		/*ItemStack stack = input.createItemStack(1);
		if (checkSize && count > stack.getMaxStackSize()) throw STACK_TOO_BIG.create(input.item().getRegisteredName(), stack.getMaxStackSize());
		stack.setCount(count);
		return stack;
		*///?} else {
		return input.createItemStack(count, checkSize);
		//?}
	}

	/**
	 * Calls {@code onChange} on every change. The same on all versions: 26.x has no container listeners, and
	 * {@code setChanged()} is the one trigger everywhere.
	 */
	public static SimpleContainer listeningContainer(int size, Runnable onChange) {
		return new SimpleContainer(size) {
			@Override
			public void setChanged() {
				super.setChanged();
				onChange.run();
			}
		};
	}
}
