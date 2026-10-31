package dev.smpeconomy.core.test;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.compat.VanillaTrades;

/**
 * The helpers mean the same on every Minecraft version. A port that only changes the switch in {@code Mc} must pass
 * these unchanged.
 */
final class CompatSelfTests {
	private static final String F = "core";

	private CompatSelfTests() {}

	static void register() {
		SelfTests.register(F, "compat-mc", CompatSelfTests::mc);
		SelfTests.register(F, "compat-vanilla-trades", CompatSelfTests::trades);
		SelfTests.register(F, "compat-item-upgrade", CompatSelfTests::itemUpgrade);
	}

	/** Real entry from auctions.json on a 1.21.11 server (DataVersion 4671): book with Blast Protection IV. */
	static final String BOOK_1_21_11 = "H4sIAAAAAAAA/0WMQQrCQAxFv7aCxJ0rEbyIGzeewW1J04iDTlJm4v0dRejy8/57BBD6FJoJJJ5nN7WohFNOplL4HucaXnQa1OTBFvnLOxwXPr64xjAXD5VIbgB6oMNG/G3R1mqLdZpwWJR/q1VH92f77q4cfNNSf/7+gg/GKWd4mQAAAA==";
	/**
	 * 1.21.11 format (DataVersion 4671): diamond sword "Probe", Sharpness III + Unbreaking II, 5 damage, repair cost 3.
	 */
	static final String SWORD_1_21_11 = "H4sIAAAAAAAC/02OOw7CMBAFHzGRkGkQ37vQ0HAAKtpo4yyJhbwb2Y64Pk6VtDMaaSxgsH9SpjfH5FWA48Ni6zOHHSrf4Ra8sIv0yffOU1DpmvTT2BnUTifJADYW1mkYVVhysrguCYsbSHKYhcFpEWmgOAqnVHpjcF7MJG1k+nrpi6rK3mF1QIF6Lrw2uCw48kg+Nk7TvGN2a+emlDU0QoFRv6K2c/4HjVzwBvcAAAA=";
	/** 1.21.11 format (DataVersion 4671): shulker box with 7 diamonds (slot 0) and 64 oak logs (slot 5). */
	static final String BOX_1_21_11 = "H4sIAAAAAAAC/3XOsQ7CMAwE0KNpBYQJED/DAgPfwFqZNEDUxEZJKvH5NANqGRgt3buzBhQ2F8p0tTE5YWB30qhdtmGFynU4BMfWRLrnY3oOvrexvclboTEycAaw0NBGwkvYck5r7CdghDONV9RjrlKok5dC8LOwnUDnKAh3s/ZlefDrmn9OqG+9PGbuXGbwAT+e0h3fAAAA";

	/**
	 * Mod data survives a Minecraft update: item strings from 1.21.11 load on any version, newer ones through DataFixer
	 * ({@code ItemCodec}), then round-trip on the running version.
	 */
	private static void itemUpgrade(TestContext ctx) {
		var enchantments = ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		ItemStack book = dev.smpeconomy.core.ItemCodec.decode(BOOK_1_21_11);
		isTrue(book.is(Items.ENCHANTED_BOOK) && book.getCount() == 1, "book loaded");
		eq(4, book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)
			.getLevel(enchantments.getOrThrow(Enchantments.BLAST_PROTECTION)), "book: Blast Protection IV");

		ItemStack sword = dev.smpeconomy.core.ItemCodec.decode(SWORD_1_21_11);
		isTrue(sword.is(Items.DIAMOND_SWORD) && sword.getCount() == 1, "sword loaded");
		ItemEnchantments ench = sword.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		eq(3, ench.getLevel(enchantments.getOrThrow(Enchantments.SHARPNESS)), "sword: Sharpness III");
		eq(2, ench.getLevel(enchantments.getOrThrow(Enchantments.UNBREAKING)), "sword: Unbreaking II");
		eq(2, ench.size(), "sword: two enchantments");
		eq(5, sword.getDamageValue(), "sword: damage");
		eq(3, (int) sword.getOrDefault(DataComponents.REPAIR_COST, 0), "sword: repair cost");
		isTrue(sword.getCustomName() != null && sword.getCustomName().getString().equals("Probe"), "sword: name");

		ItemStack box = dev.smpeconomy.core.ItemCodec.decode(BOX_1_21_11);
		isTrue(box.is(Items.SHULKER_BOX), "shulker box loaded");
		List<ItemStack> inside = Mc.containerItems(box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
		eq(2, inside.size(), "shulker box: two stacks");
		isTrue(inside.stream().anyMatch(s -> s.is(Items.DIAMOND) && s.getCount() == 7), "shulker box: 7 diamonds");
		isTrue(inside.stream().anyMatch(s -> s.is(Items.OAK_LOG) && s.getCount() == 64), "shulker box: 64 oak logs");

		for (ItemStack s : List.of(book, sword, box)) {
			isTrue(ItemStack.matches(s, dev.smpeconomy.core.ItemCodec.decode(dev.smpeconomy.core.ItemCodec.encode(s))), "round trip " + s.getItem());
		}
		ctx.detail("book, sword, shulker box from 1.21.11 loaded (MC " + Snapshot.mcVersion() + ")");
	}

	private static void mc(TestContext ctx) {
		// Container changes report themselves (SellSession relies on it for journal and preview).
		int[] changes = {0};
		SimpleContainer c = Mc.listeningContainer(9, () -> changes[0]++);
		eq(0, changes[0], "new: no change reported");
		c.setItem(0, new ItemStack(Items.WHEAT, 3));
		isTrue(changes[0] >= 1, "setItem reports");
		int before = changes[0];
		c.removeItem(0, 1);
		isTrue(changes[0] > before, "removeItem reports");
		eq(9, c.getContainerSize(), "size");

		// Component patch: set = true, removed default component = false, unchanged = absent
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		sword.set(DataComponents.CUSTOM_NAME, Component.literal("x"));
		sword.remove(DataComponents.ATTRIBUTE_MODIFIERS);
		Map<DataComponentType<?>, Boolean> changesMap = Mc.patchChanges(sword.getComponentsPatch());
		eq(Boolean.TRUE, changesMap.get(DataComponents.CUSTOM_NAME), "name set");
		eq(Boolean.FALSE, changesMap.get(DataComponents.ATTRIBUTE_MODIFIERS), "attributes removed");
		isFalse(changesMap.containsKey(DataComponents.DAMAGE), "damage unchanged");
		isTrue(Mc.patchSets(sword.getComponentsPatch(), DataComponents.CUSTOM_NAME), "patchSets: set");
		isFalse(Mc.patchSets(sword.getComponentsPatch(), DataComponents.ATTRIBUTE_MODIFIERS), "patchSets: removed does not count");
		isFalse(Mc.patchSets(sword.getComponentsPatch(), DataComponents.DAMAGE), "patchSets: unchanged");
		isTrue(Mc.patchChanges(new ItemStack(Items.STONE).getComponentsPatch()).isEmpty(), "fresh stack: no changes");

		ItemContainerContents box = ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 2)));
		List<ItemStack> inBox = Mc.containerItems(box);
		eq(1, inBox.size(), "container: non-empty only");
		isTrue(inBox.get(0).is(Items.DIAMOND) && inBox.get(0).getCount() == 2, "container: diamond x2");
		List<ItemStack> inBundle = Mc.bundleItems(Mc.bundle(List.of(new ItemStack(Items.ARROW, 5))));
		isTrue(inBundle.size() == 1 && inBundle.get(0).is(Items.ARROW) && inBundle.get(0).getCount() == 5, "bundle round trip");

		isTrue(Mc.craftingRemainder(Items.WATER_BUCKET).is(Items.BUCKET), "water bucket gives bucket");
		isTrue(Mc.craftingRemainder(Items.DIAMOND).isEmpty(), "diamond gives nothing");

		ChunkPos pos = new ChunkPos(3, -4);
		eq(Mc.chunkKey(3, -4), Mc.chunkKey(pos), "chunk key");
		eq(3, Mc.chunkX(pos), "Chunk-X");
		eq(-4, Mc.chunkZ(pos), "Chunk-Z");

		eq('6', Mc.formatChar(ChatFormatting.GOLD), "section char gold");
		eq(0xFFAA00L, (long) Mc.formatRgb(ChatFormatting.GOLD), "RGB gold");
		eq(null, Mc.formatRgb(ChatFormatting.BOLD), "bold: no color");
		isTrue(Mc.itemName(Items.DIAMOND).getContents() instanceof TranslatableContents t && t.getKey().equals("item.minecraft.diamond"),
			"item name translatable");
		// Name of the item type, not of a stack without contents ("Uncraftable Potion").
		for (Item named : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW, Items.SHIELD, Items.PLAYER_HEAD)) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(named).getPath();
			String key = Mc.itemName(named).getContents() instanceof TranslatableContents t ? t.getKey() : "";
			isTrue(key.equals("item.minecraft." + id) || key.equals("block.minecraft." + id), "item name of " + id + ": " + key);
		}
		ServerBossEvent bar = Mc.bossEvent(Component.literal("t"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
		eq(BossEvent.BossBarColor.RED, bar.getColor(), "boss bar color");
		eq(BossEvent.BossBarOverlay.NOTCHED_10, bar.getOverlay(), "boss bar style");

		// "#minecraft:dirt" in configs means dirt plus grass, podzol, mycelium, moss and mud on every version.
		TagKey<Block> dirt = Mc.configTag(Registries.BLOCK, Identifier.withDefaultNamespace("dirt"));
		for (Block b : List.of(Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM,
				Blocks.MOSS_BLOCK, Blocks.MUD)) {
			isTrue(b.defaultBlockState().is(dirt), "#dirt from configs contains " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b));
		}
		isFalse(Blocks.STONE.defaultBlockState().is(dirt), "#dirt without stone");
		isTrue(Blocks.SAND.defaultBlockState().is(Mc.configTag(Registries.BLOCK, Identifier.withDefaultNamespace("sand"))), "other tags unchanged");
		// The same for item stats (crafted, used, broken): the ten items of 1.21.11's #dirt.
		List<Item> dirtItems = Mc.configTagMembers(net.minecraft.core.registries.BuiltInRegistries.ITEM, Identifier.withDefaultNamespace("dirt"))
			.stream().map(net.minecraft.core.Holder::value).toList();
		for (Item i : List.of(Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.GRASS_BLOCK, Items.PODZOL, Items.MYCELIUM, Items.MOSS_BLOCK,
				Items.PALE_MOSS_BLOCK, Items.MUD, Items.MUDDY_MANGROVE_ROOTS)) {
			isTrue(dirtItems.contains(i), "item #dirt from configs contains " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i));
		}
		eq(10, dirtItems.size(), "item #dirt: exactly the ten items");
		eq(10, dev.smpeconomy.features.tasks.StatRefs.resolve("crafted:#minecraft:dirt").size(), "stat crafted:#minecraft:dirt");

		var overworld = ctx.overworld();
		long saved = Mc.dayTime(overworld);
		ctx.cleanup(() -> Mc.setDayTime(overworld, saved));
		long base = saved - Math.floorMod(saved, 24_000L);
		Mc.setDayTime(overworld, base + 13_500);
		eq(13_500L, Math.floorMod(Mc.dayTime(overworld), 24_000L), "day time set and read");
		Mc.setDayTime(overworld, saved);
		ctx.detail("container listener, patch, contents, remainder, chunk, text, boss bar, config tags, day time");
	}

	private static void trades(TestContext ctx) {
		VanillaTrades.Result all = VanillaTrades.offers(ctx.overworld(), VanillaTrades.Kind.ALL, VanillaTrades.Experimental.ALWAYS, null);
		VanillaTrades.Result emeralds = VanillaTrades.offers(ctx.overworld(), VanillaTrades.Kind.EMERALDS_FOR_ITEMS,
			VanillaTrades.Experimental.ALWAYS, null);
		VanillaTrades.Result free = VanillaTrades.offers(ctx.overworld(), VanillaTrades.Kind.ENTITY_FREE, VanillaTrades.Experimental.IF_ENABLED, null);
		isTrue(all.offers().size() > 100, "ALL: many offers (" + all.offers().size() + ")");
		// Experimental.ALWAYS reads rebalance trades even in worlds without the datapack.
		VanillaTrades.Result enabledOnly = VanillaTrades.offers(ctx.overworld(), VanillaTrades.Kind.ALL, VanillaTrades.Experimental.IF_ENABLED, null);
		if (ctx.overworld().getServer().getPackRepository().getSelectedIds().contains("trade_rebalance")) {
			isTrue(all.offers().size() >= enabledOnly.offers().size(), "ALWAYS: at least the active trades");
		} else {
			isTrue(all.offers().size() > enabledOnly.offers().size(), "ALWAYS: more than without rebalance (" + all.offers().size() + " > " + enabledOnly.offers().size() + ")");
		}
		isTrue(all.offers().stream().anyMatch(VanillaTrades.Offer::wandering), "ALL: wandering trader too");
		isTrue(!emeralds.offers().isEmpty() && emeralds.offers().stream().allMatch(o -> o.result().is(Items.EMERALD) && !o.costA().isEmpty()),
			"EMERALDS_FOR_ITEMS: only items for emeralds");
		isTrue(free.offers().size() > emeralds.offers().size(), "ENTITY_FREE: more than emerald purchases");
		isTrue(free.offers().stream().noneMatch(o -> o.result().isEmpty() || o.costA().isEmpty()), "ENTITY_FREE: complete offers");
		// Same seed gives same results on repeat.
		VanillaTrades.Result again = VanillaTrades.offers(ctx.overworld(), VanillaTrades.Kind.ALL, VanillaTrades.Experimental.ALWAYS, null);
		eq(all.offers().size(), again.offers().size(), "repeatable: count");
		eq(all.skipped(), again.skipped(), "repeatable: skipped");
		for (int i = 0; i < all.offers().size(); i++) {
			VanillaTrades.Offer a = all.offers().get(i);
			VanillaTrades.Offer b = again.offers().get(i);
			isTrue(ItemStack.matches(a.costA(), b.costA()) && ItemStack.matches(a.costB(), b.costB()) && ItemStack.matches(a.result(), b.result()),
				"repeatable: offer " + i);
		}
		ctx.detail("ALL " + all.offers().size() + " (" + all.skipped() + " skipped), emerald purchases " + emeralds.offers().size()
			+ ", without entity " + free.offers().size());
	}
}
