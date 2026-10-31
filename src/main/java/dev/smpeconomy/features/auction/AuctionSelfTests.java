package dev.smpeconomy.features.auction;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;
import static dev.smpeconomy.core.test.Assert.notNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.auction.AuctionService.CreateResult;
import dev.smpeconomy.features.auction.AuctionService.Listing;
import dev.smpeconomy.features.auction.AuctionService.NpcPayer;
import dev.smpeconomy.features.auction.AuctionService.RemoveReason;
import dev.smpeconomy.features.auction.AuctionService.Result;
import dev.smpeconomy.features.auction.AuctionService.Sort;
import dev.smpeconomy.features.auction.AuctionStore.Claim;
import dev.smpeconomy.features.auction.AuctionStore.Entry;

/**
 * GUIs are covered by e2e/auction.mjs. The TestPlayers cleanup hook removes test players' listings; NPC
 * listings and price modifiers from {@link #SRC} are removed by the tests themselves.
 */
final class AuctionSelfTests {
	private static final String F = "auction";
	static final String SRC = "auction.selftest";
	private static final Item DIAMOND = net.minecraft.world.item.Items.DIAMOND;
	private static final Item EMERALD = net.minecraft.world.item.Items.EMERALD;
	private static final Item DIRT = net.minecraft.world.item.Items.DIRT;
	private static final Item STONE = net.minecraft.world.item.Items.STONE;
	private static final long RICH = 100_000_000_00L;

	private AuctionSelfTests() {}

	static void register() {
		SelfTests.register(F, "list-buy", AuctionSelfTests::listBuy);
		SelfTests.register(F, "cannot-buy-own", AuctionSelfTests::cannotBuyOwn);
		SelfTests.register(F, "buy-after-cancel-fails", AuctionSelfTests::buyAfterCancel);
		SelfTests.register(F, "cancel-without-space", AuctionSelfTests::cancelWithoutSpace);
		SelfTests.register(F, "expiry-claim-bin", AuctionSelfTests::expiryClaim);
		SelfTests.register(F, "expiry-reentrant-listener", AuctionSelfTests::expiryReentrant);
		SelfTests.register(F, "persistence-roundtrip", AuctionSelfTests::persistence);
		SelfTests.register(F, "components-roundtrip", AuctionSelfTests::components);
		SelfTests.register(F, "limits", AuctionSelfTests::limits);
		SelfTests.register(F, "buy-checks", AuctionSelfTests::buyChecks);
		SelfTests.register(F, "tax-to-treasury", AuctionSelfTests::taxToTreasury);
		SelfTests.register(F, "npc-listing-purchase", AuctionSelfTests::npcListingPurchase);
		SelfTests.register(F, "npc-listing-priceguard", AuctionSelfTests::npcListingPriceGuard);
		SelfTests.register(F, "npc-buy-player-listing", AuctionSelfTests::npcBuyPlayerListing);
		SelfTests.register(F, "npc-buy-guards", AuctionSelfTests::npcBuyGuards);
		SelfTests.register(F, "remove-npc-listing", AuctionSelfTests::removeNpc);
		SelfTests.register(F, "listeners", AuctionSelfTests::listeners);
		SelfTests.register(F, "search-sort-filter", AuctionSelfTests::searchSortFilter);
		SelfTests.register(F, "unreadable-item-kept", AuctionSelfTests::unreadable);
		SelfTests.register(F, "double-buy", AuctionSelfTests::doubleBuy);
		SelfTests.register(F, "notifications", AuctionSelfTests::notifications);
		SelfTests.register(F, "api-escrow-listing", AuctionSelfTests::apiEscrow);
		SelfTests.register(F, "store-repair", AuctionSelfTests::storeRepair);
		SelfTests.register(F, "item-size-limit", AuctionSelfTests::itemSizeLimit);
		SelfTests.register(F, "gui-preview-light", AuctionSelfTests::guiPreviewLight);
		SelfTests.register(F, "lore-limit", AuctionSelfTests::loreLimit);
		SelfTests.register(F, "npc-buy-below-sale-floor", AuctionSelfTests::npcBuyBelowSaleFloor);
		SelfTests.register(F, "npc-buy-contents-damage", AuctionSelfTests::npcBuyContentsDamage);
		SelfTests.register(F, "sell-confirm-total-price", AuctionSelfTests::sellConfirmTotalPrice);
		SelfTests.register(F, "sell-button-flow", AuctionSelfTests::sellButtonFlow);
		SelfTests.register(F, "aliases", AuctionSelfTests::aliases);
		SelfTests.register(F, "npc-bargain-floor", AuctionSelfTests::npcBargainFloor);
		SelfTests.register(F, "config-migration", ctx -> configMigration());
	}

	private static FakePlayer player(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		EconomyService.ensureAccount(p.getUUID(), name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static void hand(FakePlayer p, ItemStack stack) {
		Inventory inv = p.getInventory();
		inv.setItem(inv.getSelectedSlot(), stack.copy());
	}

	private static ItemStack handItem(FakePlayer p) {
		return p.getInventory().getSelectedItem();
	}

	private static Listing list(FakePlayer p, ItemStack stack, long price) {
		hand(p, stack);
		CreateResult r = AuctionService.listFromHand(p, price);
		eq(Result.OK, r.result(), "list " + Items.id(stack.getItem()));
		isTrue(handItem(p).isEmpty(), "hand empty after listing");
		return r.listing();
	}

	private static long balance(FakePlayer p) {
		return EconomyService.balance(p.getUUID());
	}

	private static int count(FakePlayer p, Item item) {
		return Items.count(p, s -> s.is(item));
	}

	private static void richTreasury(TestContext ctx) {
		ctx.preserveTreasury();
		EconomyService.set(Treasury.ID, RICH);
	}

	private static long flowIncome(String reason) {
		Treasury.Flow f = Treasury.stats24h().get(reason);
		return f == null ? 0 : f.income();
	}

	private static long flowExpense(String reason) {
		Treasury.Flow f = Treasury.stats24h().get(reason);
		return f == null ? 0 : f.expense();
	}

	private static void fill(FakePlayer p) {
		for (int i = 0; i < Items.MAIN_SIZE; i++) p.getInventory().setItem(i, new ItemStack(STONE, 64));
	}

	private static Holder<Enchantment> ench(TestContext ctx, net.minecraft.resources.ResourceKey<Enchantment> key) {
		return ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	private static ItemStack fancySword(TestContext ctx) {
		ItemStack sword = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
		sword.enchant(ench(ctx, Enchantments.SHARPNESS), 5);
		sword.enchant(ench(ctx, Enchantments.MENDING), 1);
		sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur").withStyle(ChatFormatting.GOLD));
		sword.setDamageValue(17);
		return sword;
	}

	private static ItemStack mendingBook(TestContext ctx) {
		ItemStack book = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
		ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		m.set(ench(ctx, Enchantments.MENDING), 1);
		book.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
		return book;
	}

	private static ItemStack filledShulker(TestContext ctx) {
		ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(fancySword(ctx), new ItemStack(DIAMOND, 64), mendingBook(ctx))));
		box.set(DataComponents.CUSTOM_NAME, Component.literal("Treasure Chest"));
		return box;
	}

	/** A full page of 1024 CJK characters, 3 bytes each in NBT. */
	private static final String CJK_PAGE = "漢".repeat(1024);

	private static ItemStack writableBook(int pages) {
		List<Filterable<String>> list = new ArrayList<>();
		for (int i = 0; i < pages; i++) list.add(Filterable.passThrough(CJK_PAGE));
		ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
		book.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(list));
		return book;
	}

	private static ItemStack writtenBook(int pages) {
		List<Filterable<Component>> list = new ArrayList<>();
		for (int i = 0; i < pages; i++) list.add(Filterable.passThrough(Component.literal(CJK_PAGE)));
		ItemStack book = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Diary"), "AhSeller", 0, list, true));
		return book;
	}

	private static ItemStack shulkerWith(ItemStack each, int stacks) {
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < stacks; i++) items.add(each.copy());
		ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
		return box;
	}

	private static String loreText(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Component c : lore.lines()) sb.append(c.getString()).append('\n');
		return sb.toString();
	}

	/** Records whether it was asked; pays from the treasury. */
	private static NpcPayer spyPayer(boolean[] called) {
		return new NpcPayer() {
			@Override
			public boolean withdraw(long cents) {
				called[0] = true;
				return Treasury.tryWithdraw(cents, Treasury.R_NPC_BUY);
			}

			@Override
			public void refund(long cents) {
				Treasury.deposit(cents, Treasury.R_NPC_BUY);
			}
		};
	}

	private static boolean pendingContains(FakePlayer p, String text) {
		for (String m : PlayerDataService.getPendingMessages(p.getUUID())) {
			if (m.contains(text)) return true;
		}
		return false;
	}

	private static void listBuy(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 200_00);
		Listing l = list(a, new ItemStack(DIAMOND, 16), 100_00);
		eq(16, l.count(), "listing count");
		eq(100_00L, l.price(), "price");
		isFalse(l.npc(), "player listing");
		eq(a.getUUID(), l.seller(), "seller");
		eq(1, AuctionService.activeCount(a.getUUID()), "1 active listing");
		isTrue(AuctionService.activeListings().stream().anyMatch(x -> x.id() == l.id()), "in activeListings");
		long expectedEnd = l.createdAt() + AuctionConfig.get().durationHours * AuctionService.HOUR_MS;
		eq(expectedEnd, l.expiresAt(), "duration 48 h");

		eq(Result.OK, AuctionService.buyListing(b, l.id()), "buy");
		eq(16, count(b, DIAMOND), "buyer has 16 diamonds");
		eq(100_00L, balance(b), "buyer pays $100");
		long tax = AuctionService.taxFor(100_00);
		eq(300L, tax, "3 % tax");
		eq(100_00L - tax, balance(a), "seller gets price minus tax");
		isTrue(AuctionService.listing(l.id()).isEmpty(), "listing gone");
		eq(0, AuctionService.activeCount(a.getUUID()), "no active listings left");
		eq(0, ctx.drops(b).size(), "nothing dropped");
		eq(0, count(a, DIAMOND), "seller has no diamonds left");
		isTrue(pendingContains(a, "Sold "), "offline seller notified on join");
		ctx.detail("16 diamonds for $100, seller $97, tax $3");
	}

	private static void cannotBuyOwn(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 500_00);
		Listing l = list(a, new ItemStack(DIAMOND, 3), 50_00);
		eq(Result.OWN_LISTING, AuctionService.buyListing(a, l.id()), "own listing");
		eq(500_00L, balance(a), "no money moved");
		eq(0, count(a, DIAMOND), "no item returned");
		isTrue(AuctionService.listing(l.id()).isPresent(), "listing stays");
		eq(1, AuctionService.activeCount(a.getUUID()), "still active");
	}

	private static void buyAfterCancel(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 1_000_00);
		Listing l = list(a, new ItemStack(DIAMOND, 5), 20_00);
		eq(Result.NOT_ALLOWED, AuctionService.cancelListing(b, l.id()), "cancel someone else's listing");
		eq(Result.OK, AuctionService.cancelListing(a, l.id()), "cancel");
		eq(5, count(a, DIAMOND), "item returned");
		eq(Result.NOT_FOUND, AuctionService.buyListing(b, l.id()), "buy after cancel");
		eq(1_000_00L, balance(b), "buyer money unchanged");
		eq(0L, balance(a), "seller money unchanged");
		eq(0, count(b, DIAMOND), "buyer has nothing");
		eq(Result.NOT_FOUND, AuctionService.cancelListing(a, l.id()), "second cancel");
		eq(5, count(a, DIAMOND), "not returned twice");
		eq(0, AuctionService.claimCount(a.getUUID()), "nothing in claim bin");
	}

	private static void cancelWithoutSpace(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		ItemStack sword = fancySword(ctx);
		Listing l = list(a, sword, 10_00);
		fill(a);
		eq(Result.STORED, AuctionService.cancelListing(a, l.id()), "no space: goes to claim bin");
		eq(1, AuctionService.claimCount(a.getUUID()), "1 claim bin entry");
		eq(0, ctx.drops(a).size(), "nothing dropped");
		long claimId = AuctionService.claims(a.getUUID()).get(0).id();
		eq(Result.NO_SPACE, AuctionService.claim(a, claimId), "claim without space");
		a.getInventory().setItem(5, ItemStack.EMPTY);
		eq(Result.OK, AuctionService.claim(a, claimId), "claim with space");
		isTrue(ItemStack.matches(sword, a.getInventory().getItem(5)), "sword returned unchanged");
		eq(0, AuctionService.claimCount(a.getUUID()), "claim bin empty");
	}

	private static void expiryClaim(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 1_000_00);
		Listing l = list(a, new ItemStack(DIAMOND, 7), 30_00);
		Entry e = AuctionStore.findListing(l.id());
		notNull(e, "entry");
		e.expiresAt = AuctionService.now() - 1;
		isTrue(AuctionService.listing(l.id()).isEmpty(), "expired means no longer visible");
		isFalse(AuctionService.browse("", Sort.NEWEST, false).stream().anyMatch(x -> x.id() == l.id()), "not in browse");
		eq(Result.NOT_FOUND, AuctionService.buyListing(b, l.id()), "expired cannot be bought");
		eq(Result.NOT_FOUND, AuctionService.buyListingAsNpc(l.id(), NpcPayer.treasury()), "expired cannot be bought by NPC");
		isTrue(AuctionService.processExpired() >= 1, "expiry processed");
		isTrue(AuctionStore.findListing(l.id()) == null, "removed from listings");
		eq(0, AuctionService.activeCount(a.getUUID()), "no active ones");
		eq(1, AuctionService.claimCount(a.getUUID()), "in claim bin");
		isTrue(pendingContains(a, "Expired"), "seller notified");
		eq(1_000_00L, balance(b), "no money moved");

		long claimId = AuctionService.claims(a.getUUID()).get(0).id();
		eq(Result.NOT_FOUND, AuctionService.claim(b, claimId), "someone else's claim bin");
		fill(a);
		eq(Result.NO_SPACE, AuctionService.claim(a, claimId), "no space");
		eq(1, AuctionService.claimCount(a.getUUID()), "stays in claim bin");
		a.getInventory().clearContent();
		eq(Result.OK, AuctionService.claim(a, claimId), "claim");
		eq(7, count(a, DIAMOND), "7 diamonds returned");
		eq(Result.NOT_FOUND, AuctionService.claim(a, claimId), "second claim");
		eq(7, count(a, DIAMOND), "not twice");

		a.getInventory().clearContent();
		list(a, new ItemStack(DIAMOND, 3), 10_00);
		list(a, new ItemStack(DIAMOND, 4), 10_00);
		eq(2, AuctionService.forceExpire(a.getUUID()), "admin expiry");
		eq(2, AuctionService.claimCount(a.getUUID()), "both in claim bin");
		int[] r = AuctionService.claimAll(a);
		eq(2, r[0], "both claimed");
		eq(0, r[1], "nothing left");
		eq(7, count(a, DIAMOND), "all diamonds returned");
		ctx.detail("expiry to claim bin, claim only with space, exactly once");
	}

	/** A listener removes another due listing during expiry; it must not reach the claim bin twice. */
	private static void expiryReentrant(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		Listing l1 = list(a, new ItemStack(DIAMOND, 1), 1_00);
		Listing l2 = list(a, new ItemStack(DIAMOND, 2), 1_00);
		AuctionStore.findListing(l1.id()).expiresAt = AuctionService.now() - 2;
		AuctionStore.findListing(l2.id()).expiresAt = AuctionService.now() - 1;
		Hooks.Registration reg = AuctionService.onListingRemoved((l, reason) -> {
			if (l.id() == l1.id()) AuctionService.adminRemove(l2.id());
			if (l.id() == l2.id()) AuctionService.adminRemove(l1.id());
		});
		ctx.cleanup(reg::remove);
		AuctionService.processExpired();
		reg.remove();
		eq(2, AuctionService.claimCount(a.getUUID()), "exactly 2 claim bin entries (no duplicate)");
		isTrue(AuctionStore.findListing(l1.id()) == null && AuctionStore.findListing(l2.id()) == null, "both listings gone");
		int[] r = AuctionService.claimAll(a);
		eq(2, r[0], "2 claimed");
		eq(3, count(a, DIAMOND), "exactly 3 diamonds returned");
	}

	private static void persistence(TestContext ctx) throws Exception {
		String file = "selftest-auctions.json";
		Path dir = DataStore.dataDir();
		Runnable wipe = () -> {
			try (var files = Files.list(dir)) {
				for (Path p : files.filter(f -> f.getFileName().toString().startsWith(file)).toList()) Files.deleteIfExists(p);
			} catch (Exception ignored) {
			}
		};
		ctx.cleanup(wipe);
		wipe.run();

		FakePlayer a = player(ctx, "AhSeller", 0);
		ItemStack sword = fancySword(ctx);
		ItemStack box = filledShulker(ctx);
		Listing l1 = list(a, sword, 123_45);
		Listing l2 = list(a, box, 999_99);
		Entry e1 = AuctionStore.findListing(l1.id());
		Entry e2 = AuctionStore.findListing(l2.id());

		AuctionStore.Data data = new AuctionStore.Data();
		data.nextId = 4242;
		data.listings.add(e1);
		data.listings.add(e2);
		Entry npc = new Entry();
		npc.id = 4000;
		npc.npc = true;
		npc.source = SRC;
		npc.sellerName = "§e[Market] Trader Harry";
		npc.item = ItemCodec.encode(new ItemStack(EMERALD, 5));
		npc.itemId = "minecraft:emerald";
		npc.count = 5;
		npc.price = 55_55;
		npc.createdAt = 1;
		npc.expiresAt = Long.MAX_VALUE;
		data.listings.add(npc);
		Claim c = new Claim();
		c.id = 4001;
		c.owner = a.getUUID();
		c.item = ItemCodec.encode(mendingBook(ctx));
		c.itemId = "minecraft:enchanted_book";
		c.count = 1;
		c.price = 1;
		c.createdAt = 2;
		c.reason = "cancelled";
		data.claims.add(c);

		DataStore<AuctionStore.Data> store = DataStore.unmanaged(file, AuctionStore.Data.class, AuctionStore.Data::new);
		store.load();
		store.set(data);
		isTrue(store.save(), "saved");
		DataStore<AuctionStore.Data> again = DataStore.unmanaged(file, AuctionStore.Data.class, AuctionStore.Data::new);
		again.load();
		AuctionStore.Data r = again.get();
		eq(4242L, r.nextId, "nextId");
		eq(3, r.listings.size(), "3 listings");
		eq(1, r.claims.size(), "1 claim bin entry");
		Entry r1 = r.listings.get(0);
		eq(e1.id, r1.id, "ID");
		eq(a.getUUID(), r1.seller, "seller UUID");
		eq(123_45L, r1.price, "price");
		eq(e1.expiresAt, r1.expiresAt, "expiry");
		eq(e1.createdAt, r1.createdAt, "created");
		isTrue(ItemStack.matches(sword, ItemCodec.decode(r1.item)), "sword (enchanted, named, damaged) unchanged");
		isTrue(ItemStack.matches(box, ItemCodec.decode(r.listings.get(1).item)), "shulker with contents unchanged");
		Entry rn = r.listings.get(2);
		isTrue(rn.npc, "NPC flag");
		isTrue(rn.seller == null, "NPC without seller UUID");
		eq("§e[Market] Trader Harry", rn.sellerName, "NPC name with color codes");
		eq(SRC, rn.source, "source");
		eq(Long.MAX_VALUE, rn.expiresAt, "large long values");
		Claim rc = r.claims.get(0);
		eq("cancelled", rc.reason, "reason");
		isTrue(ItemStack.matches(mendingBook(ctx), ItemCodec.decode(rc.item)), "book unchanged");

		String json = DataStore.gson().toJson(AuctionStore.data());
		AuctionStore.Data live = DataStore.gson().fromJson(json, AuctionStore.Data.class);
		Entry back = null;
		for (Entry x : live.listings) {
			if (x.id == l2.id()) back = x;
		}
		notNull(back, "test listing in live round trip");
		isTrue(ItemStack.matches(box, ItemCodec.decode(back.item)), "live round trip shulker");
		ctx.detail("file round trip (player/NPC/claim bin) + live Gson round trip");
	}

	private static void components(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 10_000_00);
		ItemStack sword = fancySword(ctx);
		ItemStack box = filledShulker(ctx);
		ItemStack book = mendingBook(ctx);
		ItemStack named = new ItemStack(DIRT, 33);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Noble Dirt"));
		List<ItemStack> originals = List.of(sword, box, book, named);
		List<Long> ids = new ArrayList<>();
		for (ItemStack s : originals) {
			Listing l = list(a, s, 1_00);
			isTrue(ItemStack.matches(s, l.item()), "listing shows the exact item " + Items.id(s.getItem()));
			ids.add(l.id());
		}
		for (long id : ids) eq(Result.OK, AuctionService.buyListing(b, id), "buy #" + id);
		for (ItemStack s : originals) {
			boolean found = false;
			for (int i = 0; i < Items.MAIN_SIZE; i++) {
				if (ItemStack.matches(s, b.getInventory().getItem(i))) found = true;
			}
			isTrue(found, "buyer has " + Items.id(s.getItem()) + " with all components");
		}
		eq(0, ctx.drops(b).size(), "nothing dropped");
		ctx.detail("enchanted+named+damaged, shulker with contents, book, named stack");
	}

	private static void limits(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		AuctionConfig cfg = AuctionConfig.get();
		ItemStack stack = new ItemStack(DIAMOND, 2);

		a.getInventory().clearContent();
		eq(Result.NO_ITEM, AuctionService.listFromHand(a, 1_00).result(), "empty hand");
		hand(a, stack);
		eq(Result.INVALID_PRICE, AuctionService.listFromHand(a, 0).result(), "price 0");
		eq(Result.INVALID_PRICE, AuctionService.listFromHand(a, -5).result(), "negative price");
		eq(Result.INVALID_PRICE, AuctionService.listFromHand(a, cfg.maxPrice + 1).result(), "above maximum");
		isTrue(ItemStack.matches(stack, handItem(a)), "item stays in hand");
		eq(Result.OK, AuctionService.listFromHand(a, cfg.maxPrice).result(), "exact maximum ok");
		hand(a, new ItemStack(net.minecraft.world.item.Items.BARRIER));
		eq(Result.INVALID_ITEM, AuctionService.listFromHand(a, 1_00).result(), "blocked item");
		eq(1, count(a, net.minecraft.world.item.Items.BARRIER), "blocked item stays");

		a.getInventory().clearContent();
		hand(a, stack);
		a.setGameMode(GameType.CREATIVE);
		try {
			eq(Result.CREATIVE, AuctionService.listFromHand(a, 1_00).result(), "creative rejected");
			isTrue(ItemStack.matches(stack, handItem(a)), "item stays (creative)");
		} finally {
			a.setGameMode(GameType.SURVIVAL);
		}

		for (int i = AuctionService.activeCount(a.getUUID()); i < cfg.maxListings; i++) list(a, new ItemStack(DIRT, 1 + i), 1_00);
		eq(cfg.maxListings, AuctionService.activeCount(a.getUUID()), "limit reached");
		hand(a, stack);
		eq(Result.LIMIT_REACHED, AuctionService.listFromHand(a, 1_00).result(), "above the limit");
		isTrue(ItemStack.matches(stack, handItem(a)), "item stays (limit)");
		eq(cfg.maxListings, AuctionService.activeCount(a.getUUID()), "no more listings");

		FakePlayer c = player(ctx, "AhThird", 0);
		String dirt = ItemCodec.encode(new ItemStack(DIRT));
		for (int i = 0; i < cfg.maxClaims; i++) {
			Claim cl = new Claim();
			cl.id = AuctionStore.nextId();
			cl.owner = c.getUUID();
			cl.item = dirt;
			cl.itemId = "minecraft:dirt";
			cl.count = 1;
			AuctionStore.data().claims.add(cl);
		}
		AuctionStore.markDirty();
		hand(c, stack);
		eq(Result.CLAIM_BIN_FULL, AuctionService.listFromHand(c, 1_00).result(), "full claim bin");
		isTrue(ItemStack.matches(stack, handItem(c)), "item stays (claim bin)");
		ctx.detail("hand/price/maximum/blocked/creative/" + cfg.maxListings + " listings/claim bin " + cfg.maxClaims);
	}

	private static void sellConfirmTotalPrice(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		UUID id = a.getUUID();
		ctx.cleanup(() -> AuctionSellConfirm.clear(id));
		AuctionSellConfirm.clear(id);
		long market = Services.marketValue(DIAMOND).orElse(0);
		check(market > 0, "diamond has a market value");
		int slot = a.getInventory().getSelectedSlot();
		int otherSlot = (slot + 1) % 9;
		long window = AuctionConfig.get().sellConfirmSeconds * 1000L;

		// 64 diamonds for $80, meant as a unit price
		ItemStack stack = new ItemStack(DIAMOND, 64);
		hand(a, stack);
		eq(Result.OK, AuctionService.checkListFromHand(a, 80_00), "precheck ok");
		isTrue(ItemStack.matches(stack, handItem(a)), "precheck takes nothing from the hand");
		AuctionSellConfirm.Quote q = AuctionSellConfirm.quote(id, stack, 80_00);
		isTrue(q.needsConfirm(), "stack needs confirmation");
		isTrue(q.belowMarket(), "far below market value detected (" + Money.format(market) + "/each)");
		long sell = PriceGuard.effectiveSellPrice(id, DIAMOND).orElse(0);
		if (sell * 64 > 80_00) isTrue(q.belowSell(), "below /sell price detected");
		eq(AuctionSellConfirm.Decision.CONFIRM, AuctionSellConfirm.decide(id, slot, stack, q, 1_000), "preview first");
		eq(AuctionSellConfirm.Decision.LIST, AuctionSellConfirm.decide(id, slot, stack, q, 1_000 + window), "repeat within window");
		eq(AuctionSellConfirm.Decision.CONFIRM, AuctionSellConfirm.decide(id, slot, stack, q, 1_000 + window + 1),
			"confirmation works only once");
		eq(AuctionSellConfirm.Decision.CONFIRM, AuctionSellConfirm.decide(id, slot, stack, q, 1_000 + 3 * window), "expired: new preview");

		long t = 1_000_000;
		long fair = market * 64;
		AuctionSellConfirm.Quote q2 = AuctionSellConfirm.quote(id, stack, fair);
		isTrue(q2.needsConfirm() && !q2.belowMarket(), "stack at market value: confirmation without market warning");
		eq(AuctionSellConfirm.Decision.CONFIRM, AuctionSellConfirm.decide(id, slot, stack, q2, t + 1), "other price: new preview");
		ItemStack fewer = new ItemStack(DIAMOND, 63);
		AuctionSellConfirm.Quote q3 = AuctionSellConfirm.quote(id, fewer, fair);
		eq(AuctionSellConfirm.Decision.CHANGED, AuctionSellConfirm.decide(id, slot, fewer, q3, t + 2), "other count: confirm again");
		eq(AuctionSellConfirm.Decision.CHANGED, AuctionSellConfirm.decide(id, otherSlot, fewer, q3, t + 3), "other slot: confirm again");
		eq(AuctionSellConfirm.Decision.LIST, AuctionSellConfirm.decide(id, otherSlot, fewer, q3, t + 4), "then confirmed");

		ItemStack one = new ItemStack(DIAMOND);
		AuctionSellConfirm.Quote q4 = AuctionSellConfirm.quote(id, one, market * 3);
		isFalse(q4.needsConfirm(), "1 item at 3x market value: no confirmation");
		eq(AuctionSellConfirm.Decision.LIST, AuctionSellConfirm.decide(id, slot, one, q4, t + 5), "1 item immediately");
		AuctionSellConfirm.Quote q5 = AuctionSellConfirm.quote(id, one, 1);
		isTrue(q5.warn() && q5.needsConfirm(), "1 item for $0.01: warning + confirmation");
		eq(AuctionSellConfirm.Decision.CONFIRM, AuctionSellConfirm.decide(id, slot, one, q5, t + 6), "dump price: preview");
		AuctionSellConfirm.clear(id);

		eq("$1.25", AuctionSellConfirm.unitText(80_00, 64), "unit price exact");
		eq("≈ $1.56", AuctionSellConfirm.unitText(100_00, 64), "unit price rounded");
		eq("< $0.01", AuctionSellConfirm.unitText(1, 64), "unit price below a cent");
		eq("$80.00", AuctionSellConfirm.unitText(80_00, 1), "single item");
		for (long c : new long[] {1, 80_00, 80_50, 5_120_00, 123_456_789_01L}) {
			eq(c, Money.parse(Money.commandAmount(c)).orElse(-1), "click amount " + c);
		}

		Listing l = list(a, stack, 80_00);
		eq(64, l.count(), "whole stack listed");
		eq(80_00L, l.price(), "price is the total");
		ctx.detail("stack/warning price/window/hand change/slot change; market value " + Money.format(market) + ", /sell " + Money.format(sell));
	}

	private static void buyChecks(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 49_99);
		Listing l = list(a, new ItemStack(DIAMOND, 10), 50_00);
		eq(Result.INSUFFICIENT_FUNDS, AuctionService.buyListing(b, l.id()), "not enough money");
		eq(49_99L, balance(b), "money unchanged");
		eq(0L, balance(a), "seller unchanged");
		EconomyService.set(b.getUUID(), 50_00);
		fill(b);
		eq(Result.NO_SPACE, AuctionService.buyListing(b, l.id()), "no space");
		eq(50_00L, balance(b), "no money taken without space");
		b.getInventory().clearContent();
		eq(Result.PRICE_CHANGED, AuctionService.buyListing(b, l.id(), 40_00), "price differs from display");
		eq(50_00L, balance(b), "no money on price change");
		eq(Result.NOT_FOUND, AuctionService.buyListing(b, 987_654_321L), "unknown ID");
		eq(Result.OK, AuctionService.buyListing(b, l.id(), 50_00), "buy at displayed price");
		eq(0L, balance(b), "spent exactly everything");
		eq(10, count(b, DIAMOND), "delivered");
	}

	private static void taxToTreasury(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 5_000_00);
		long price = 1_234_56;
		long tax = Money.percentOf(price, AuctionConfig.get().taxPercent);
		eq(tax, AuctionService.taxFor(price), "taxFor");
		long t0 = Treasury.balance();
		long income0 = flowIncome(Treasury.R_AH_TAX);
		long money0 = EconomyService.totalMoney();
		Listing l = list(a, new ItemStack(EMERALD, 12), price);
		eq(Result.OK, AuctionService.buyListing(b, l.id()), "buy");
		eq(t0 + tax, Treasury.balance(), "tax in treasury");
		eq(income0 + tax, flowIncome(Treasury.R_AH_TAX), "stat ah_tax");
		eq(price - tax, balance(a), "seller net");
		eq(5_000_00L - price, balance(b), "buyer gross");
		eq(money0, EconomyService.totalMoney(), "no money created (sum equal)");

		// Tax rounds down to 0: everything goes to the seller
		long t1 = Treasury.balance();
		long a1 = balance(a);
		Listing tiny = list(a, new ItemStack(DIRT, 1), 33);
		eq(0L, AuctionService.taxFor(33), "tax on 33 cents is 0");
		eq(Result.OK, AuctionService.buyListing(b, tiny.id()), "smallest purchase");
		eq(t1, Treasury.balance(), "treasury unchanged");
		eq(a1 + 33, balance(a), "seller gets everything");
		ctx.detail("$1,234.56: tax " + Money.format(tax) + " to treasury");
	}

	private static void npcListingPurchase(TestContext ctx) {
		richTreasury(ctx);
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer b = player(ctx, "AhBuyer", 0);
		ItemStack emeralds = new ItemStack(EMERALD, 3);
		CreateResult r = AuctionService.createNpcListing(emeralds, 1, 6 * AuctionService.HOUR_MS, "§e[Market] Trader Harry", SRC);
		eq(Result.OK, r.result(), "NPC listing created");
		Listing l = r.listing();
		long floor = AuctionService.npcSaleFloor(null, emeralds);
		eq(floor, l.price(), "price raised to PriceGuard floor");
		long mv = Services.marketValue(EMERALD).orElse(0);
		check(l.price() >= 3 * PriceGuard.minSystemSalePrice(new UUID(0, 0), EMERALD, mv), "≥ minSystemSalePrice × 3");
		isTrue(l.npc() && l.isNpc(), "NPC flag");
		isTrue(l.seller() == null, "no player seller");
		eq("§e[Market] Trader Harry", l.sellerName(), "display name");
		isTrue(AuctionService.browse("", Sort.NEWEST, false).stream().anyMatch(x -> x.id() == l.id()), "filter All shows NPC");
		isFalse(AuctionService.browse("", Sort.NEWEST, true).stream().anyMatch(x -> x.id() == l.id()), "filter Players Only hides NPC");
		isTrue(AuctionService.npcListings(SRC).stream().anyMatch(x -> x.id() == l.id()), "npcListings(source)");

		eq(Result.INSUFFICIENT_FUNDS, AuctionService.buyListing(b, l.id()), "without money");
		long price = l.price();
		long buyerFloor = AuctionService.npcSaleFloor(b.getUUID(), emeralds);
		check(price >= buyerFloor, "price >= buyer's floor (" + price + " ≥ " + buyerFloor + ")");
		EconomyService.set(b.getUUID(), price + 1_00);
		long t0 = Treasury.balance();
		long sale0 = flowIncome(Treasury.R_NPC_SALE);
		long money0 = EconomyService.totalMoney();
		eq(Result.OK, AuctionService.buyListing(b, l.id(), price), "buy");
		eq(1_00L, balance(b), "buyer pays full price");
		eq(t0 + price, Treasury.balance(), "100 % to treasury");
		eq(sale0 + price, flowIncome(Treasury.R_NPC_SALE), "stat npc_sale");
		eq(money0, EconomyService.totalMoney(), "sum equal (only moved)");
		eq(3, count(b, EMERALD), "emeralds delivered (item created at purchase)");
		isTrue(AuctionService.listing(l.id()).isEmpty(), "NPC listing gone");

		eq(Result.INVALID_DURATION, AuctionService.createNpcListing(emeralds, 1_00, 0, "x", SRC).result(), "duration 0");
		eq(Result.INVALID_DURATION, AuctionService.createNpcListing(emeralds, 1_00,
			(AuctionConfig.get().maxNpcDurationHours + 1) * AuctionService.HOUR_MS, "x", SRC).result(), "duration too long");
		eq(Result.NO_ITEM, AuctionService.createNpcListing(ItemStack.EMPTY, 1_00, AuctionService.HOUR_MS, "x", SRC).result(), "empty stack");
		Listing exp = AuctionService.createNpcListing(emeralds, 1_00, AuctionService.HOUR_MS, "x", SRC).listing();
		int claimsBefore = AuctionStore.data().claims.size();
		AuctionStore.findListing(exp.id()).expiresAt = AuctionService.now() - 1;
		AuctionService.processExpired();
		isTrue(AuctionStore.findListing(exp.id()) == null, "expired NPC listing removed");
		eq(claimsBefore, AuctionStore.data().claims.size(), "no claim bin for NPC listings");
		ctx.detail("NPC listing " + Money.format(price) + " to treasury, filter, expiry");
	}

	private static void npcListingPriceGuard(TestContext ctx) {
		richTreasury(ctx);
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		FakePlayer b = player(ctx, "AhBuyer", 0);
		ItemStack diamonds = new ItemStack(DIAMOND, 2);
		Listing l = AuctionService.createNpcListing(diamonds, 1, 6 * AuctionService.HOUR_MS, "§e[Market] Test Trader", SRC).listing();
		notNull(l, "NPC listing");
		long oldPrice = l.price();
		// Hype x3 raises the market value and the floor, so a buy at the old price must fail
		PriceModifiers.set(SRC, DIAMOND, 3.0, 0, 60_000, 0, "selftest");
		long newFloor = AuctionService.npcSaleFloor(b.getUUID(), diamonds);
		check(newFloor > oldPrice, "floor rises with modifier (" + oldPrice + " → " + newFloor + ")");
		EconomyService.set(b.getUUID(), RICH);
		long t0 = Treasury.balance();
		eq(Result.PRICE_CHANGED, AuctionService.buyListing(b, l.id(), oldPrice), "buy below floor rejected");
		eq(RICH, balance(b), "no money taken");
		eq(t0, Treasury.balance(), "treasury unchanged");
		eq(0, count(b, DIAMOND), "nothing delivered");
		long raised = AuctionService.listing(l.id()).orElseThrow().price();
		eq(newFloor, raised, "price raised to new floor");
		eq(Result.PRICE_CHANGED, AuctionService.buyListing(b, l.id(), oldPrice), "old expected price no longer valid");
		eq(Result.OK, AuctionService.buyListing(b, l.id(), raised), "buy at new price");
		eq(RICH - raised, balance(b), "new price paid");
		eq(t0 + raised, Treasury.balance(), "to treasury");
		eq(2, count(b, DIAMOND), "delivered");

		Hooks.Registration reg = AuctionService.addNpcSaleFloor((buyer, stack) -> 777_777);
		try {
			Listing high = AuctionService.createNpcListing(new ItemStack(DIRT, 1), 1, AuctionService.HOUR_MS, "x", SRC).listing();
			eq(777_777L, high.price(), "extra floor at listing");
		} finally {
			reg.remove();
		}
		eq(AuctionService.npcSaleFloor(null, new ItemStack(DIRT, 1)) < 777_777, true, "extra floor removed");
		ctx.detail("Hype ×3: " + Money.format(oldPrice) + " → " + Money.format(raised));
	}

	private static NpcPayer failingPayer() {
		return new NpcPayer() {
			@Override
			public boolean withdraw(long cents) {
				return false;
			}

			@Override
			public void refund(long cents) {
				throw new IllegalStateException("refund without withdraw");
			}
		};
	}

	private static void npcBuyPlayerListing(TestContext ctx) {
		richTreasury(ctx);
		FakePlayer a = player(ctx, "AhSeller", 0);
		long mv = Services.marketValue(DIAMOND).orElse(0);
		long unitCap = PriceGuard.maxNpcBuyPrice(a.getUUID(), DIAMOND, mv,
			Hooks.flag(Hooks.FLAG_AUCTION_FEVER) ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR);
		check(unitCap > 0, "diamond has an NPC max price (" + unitCap + ")");
		long price = unitCap * 2;
		Listing l = list(a, new ItemStack(DIAMOND, 2), price);
		eq(price, AuctionService.npcBuyCap(l, mv), "npcBuyCap = PriceGuard x count");
		List<Object[]> sold = new ArrayList<>();
		Hooks.Registration reg = AuctionService.onListingSold((listing, buyer, p, tax) -> sold.add(new Object[] {listing.id(), buyer, p, tax}));
		ctx.cleanup(reg::remove);

		long t0 = Treasury.balance();
		long buy0 = flowExpense(Treasury.R_NPC_BUY);
		long tax0 = flowIncome(Treasury.R_AH_TAX);
		long tax = AuctionService.taxFor(price);
		eq(Result.OK, AuctionService.buyListingAsNpc(l.id(), NpcPayer.treasury()), "NPC buys");
		eq(price - tax, balance(a), "seller gets price minus tax");
		eq(t0 - price + tax, Treasury.balance(), "treasury pays price minus tax net");
		eq(buy0 + price, flowExpense(Treasury.R_NPC_BUY), "stat npc_buy");
		eq(tax0 + tax, flowIncome(Treasury.R_AH_TAX), "tax booked");
		isTrue(AuctionService.listing(l.id()).isEmpty(), "listing gone");
		eq(0, count(a, DIAMOND), "item delivered to nobody");
		eq(0, ctx.drops(a).size(), "nothing dropped");
		eq(0, AuctionService.claimCount(a.getUUID()), "nothing in claim bin");
		eq(1, sold.size(), "onListingSold once");
		isTrue(sold.get(0)[1] == null, "null buyer means NPC");
		eq(Result.NOT_FOUND, AuctionService.buyListingAsNpc(l.id(), NpcPayer.treasury()), "second NPC buy");
		eq(price - tax, balance(a), "not paid twice");
		ctx.detail("NPC pays " + Money.format(price) + ", seller " + Money.format(price - tax));
	}

	private static void npcBuyGuards(TestContext ctx) {
		richTreasury(ctx);
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer a = player(ctx, "AhSeller", 0);
		long mv = Services.marketValue(DIAMOND).orElse(0);
		Listing probe = list(a, new ItemStack(DIAMOND, 1), 1);
		long cap = AuctionService.npcBuyCap(probe, mv);
		check(cap > 0, "cap > 0");
		eq(Result.OK, AuctionService.cancelListing(a, probe.id()), "cancel probe");
		a.getInventory().clearContent();

		Listing over = list(a, new ItemStack(DIAMOND, 1), cap + 1);
		long t0 = Treasury.balance();
		boolean[] called = {false};
		NpcPayer spy = new NpcPayer() {
			@Override
			public boolean withdraw(long cents) {
				called[0] = true;
				return Treasury.tryWithdraw(cents, Treasury.R_NPC_BUY);
			}

			@Override
			public void refund(long cents) {
				Treasury.deposit(cents, Treasury.R_NPC_BUY);
			}
		};
		eq(Result.PRICE_ABOVE_CAP, AuctionService.buyListingAsNpc(over.id(), mv, spy), "above PriceGuard cap");
		isFalse(called[0], "payer not asked");
		isTrue(AuctionService.listing(over.id()).isPresent(), "listing stays");
		eq(t0, Treasury.balance(), "treasury unchanged");
		eq(0L, balance(a), "seller unchanged");

		Listing ok = list(a, new ItemStack(DIAMOND, 1), cap);
		eq(Result.PAYMENT_FAILED, AuctionService.buyListingAsNpc(ok.id(), mv, failingPayer()), "payer declines");
		isTrue(AuctionService.listing(ok.id()).isPresent(), "listing stays after payment failure");
		eq(0L, balance(a), "seller unchanged");
		eq(Result.PRICE_ABOVE_CAP, AuctionService.buyListingAsNpc(ok.id(), 0, spy), "no NPC buy without market value");

		Listing npc = AuctionService.createNpcListing(new ItemStack(EMERALD, 1), 1_00, AuctionService.HOUR_MS, "x", SRC).listing();
		eq(Result.NPC_LISTING, AuctionService.buyListingAsNpc(npc.id(), RICH, spy), "NPC does not buy NPC listings");
		eq(Result.OK, AuctionService.cancelListing(a, ok.id()), "cancel");
		eq(Result.NOT_FOUND, AuctionService.buyListingAsNpc(ok.id(), mv, spy), "after cancel");
		isFalse(called[0], "payer never asked");
		eq(t0, Treasury.balance(), "treasury unchanged");
		ctx.detail("cap " + Money.format(cap) + ", payment failure, NPC listing, cancel");
	}

	private static void removeNpc(TestContext ctx) {
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer a = player(ctx, "AhSeller", 0);
		Listing p = list(a, new ItemStack(DIAMOND, 1), 10_00);
		Listing n1 = AuctionService.createNpcListing(new ItemStack(EMERALD, 1), 1_00, AuctionService.HOUR_MS, "x", SRC).listing();
		Listing n2 = AuctionService.createNpcListing(new ItemStack(EMERALD, 2), 1_00, AuctionService.HOUR_MS, "x", SRC).listing();
		isFalse(AuctionService.removeNpcListing(p.id()), "player listing not removable");
		isTrue(AuctionService.listing(p.id()).isPresent(), "player listing stays");
		isTrue(AuctionService.removeNpcListing(n1.id()), "NPC listing removed");
		isFalse(AuctionService.removeNpcListing(n1.id()), "remove twice");
		isTrue(AuctionService.listing(n1.id()).isEmpty(), "gone");
		eq(1, AuctionService.removeNpcListings(SRC), "rest of source");
		isTrue(AuctionService.listing(n2.id()).isEmpty(), "also gone");
		isTrue(AuctionService.listing(p.id()).isPresent(), "player listing untouched");
	}

	private static void listeners(TestContext ctx) {
		ctx.preserveTreasury();
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 1_000_00);
		List<long[]> ids = new ArrayList<>();
		List<String> events = new ArrayList<>();
		List<Hooks.Registration> regs = List.of(
			AuctionService.onListingCreated(l -> {
				ids.add(new long[] {l.id()});
				events.add("created:" + l.id() + ":" + l.isNpc());
			}),
			AuctionService.onListingSold((l, buyer, price, tax) -> {
				ids.add(new long[] {l.id()});
				events.add("sold:" + l.id() + ":" + (buyer == null ? "npc" : "player") + ":" + price + ":" + tax);
			}),
			AuctionService.onListingRemoved((l, reason) -> {
				ids.add(new long[] {l.id()});
				events.add("removed:" + l.id() + ":" + reason);
			}));
		ctx.cleanup(() -> regs.forEach(Hooks.Registration::remove));
		Hooks.Registration broken = AuctionService.onListingCreated(l -> {
			throw new IllegalStateException("listener failure (expected)");
		});
		ctx.cleanup(broken::remove);

		Listing l1 = list(a, new ItemStack(DIAMOND, 1), 10_00);
		broken.remove(); // One throwing listener is enough: keeps the stack trace to once in the log
		Listing l2 = list(a, new ItemStack(DIAMOND, 2), 20_00);
		Listing l3 = list(a, new ItemStack(DIAMOND, 3), 30_00);
		Listing n = AuctionService.createNpcListing(new ItemStack(EMERALD, 1), 1_00, AuctionService.HOUR_MS, "x", SRC).listing();
		eq(Result.OK, AuctionService.buyListing(b, l1.id()), "buy");
		eq(Result.OK, AuctionService.cancelListing(a, l2.id()), "cancel");
		AuctionStore.findListing(l3.id()).expiresAt = AuctionService.now() - 1;
		AuctionService.processExpired();
		isTrue(AuctionService.removeNpcListing(n.id()), "remove NPC");
		List<String> expected = List.of(
			"created:" + l1.id() + ":false", "created:" + l2.id() + ":false", "created:" + l3.id() + ":false", "created:" + n.id() + ":true",
			"sold:" + l1.id() + ":player:1000:" + AuctionService.taxFor(10_00),
			"removed:" + l2.id() + ":" + RemoveReason.CANCELLED, "removed:" + l3.id() + ":" + RemoveReason.EXPIRED,
			"removed:" + n.id() + ":" + RemoveReason.NPC_REMOVED);
		List<Long> ours = List.of(l1.id(), l2.id(), l3.id(), n.id());
		List<String> mine = new ArrayList<>();
		for (int i = 0; i < events.size(); i++) {
			if (ours.contains(ids.get(i)[0])) mine.add(events.get(i));
		}
		eq(expected, mine, "events exactly once and in order");
		ctx.detail(mine.size() + " events, throwing listener ignored");
	}

	private static void searchSortFilter(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		Listing sword = list(a, fancySword(ctx), 300_00);
		Listing book = list(a, mendingBook(ctx), 100_00);
		Listing dirt = list(a, new ItemStack(DIRT, 64), 200_00);
		List<Long> mine = List.of(sword.id(), book.id(), dirt.id());
		eq(List.of(sword.id()), ids(AuctionService.browse("excalibur", Sort.NEWEST, false), mine), "custom name");
		eq(List.of(sword.id()), ids(AuctionService.browse("diamond_sword", Sort.NEWEST, false), mine), "item ID");
		eq(List.of(sword.id()), ids(AuctionService.browse("Diamond Sword", Sort.NEWEST, false), mine), "English name");
		eq(List.of(sword.id(), book.id()), ids(AuctionService.browse("mending", Sort.PRICE_DESC, false), mine), "enchantment (sword + book)");
		eq(List.of(), ids(AuctionService.browse("nonexistent", Sort.NEWEST, false), mine), "no match");
		eq(List.of(book.id(), dirt.id(), sword.id()), ids(AuctionService.browse("", Sort.PRICE_ASC, false), mine), "price up");
		eq(List.of(sword.id(), dirt.id(), book.id()), ids(AuctionService.browse("", Sort.PRICE_DESC, false), mine), "price down");
		eq(List.of(dirt.id(), book.id(), sword.id()), ids(AuctionService.browse("  ", Sort.NEWEST, true), mine), "newest");
		ctx.detail("name/ID/English/enchantment + 3 sort orders");
	}

	private static List<Long> ids(List<Listing> list, List<Long> only) {
		List<Long> out = new ArrayList<>();
		for (Listing l : list) {
			if (only.contains(l.id())) out.add(l.id());
		}
		return out;
	}

	private static void unreadable(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", RICH);
		Entry e = new Entry();
		e.id = AuctionStore.nextId();
		e.seller = a.getUUID();
		e.sellerName = "AhSeller";
		e.item = "broken!!";
		e.itemId = "minecraft:diamond";
		e.count = 1;
		e.price = 1_00;
		e.createdAt = AuctionService.now();
		e.expiresAt = AuctionService.now() + AuctionService.HOUR_MS;
		AuctionStore.data().listings.add(e);
		AuctionStore.markDirty();
		long id = e.id;
		isTrue(AuctionService.listing(id).isEmpty(), "not visible");
		isFalse(AuctionService.activeListings().stream().anyMatch(l -> l.id() == id), "not in activeListings");
		eq(Result.ITEM_UNREADABLE, AuctionService.buyListing(b, id), "cannot be bought");
		eq(RICH, balance(b), "no money moved");
		eq(Result.ITEM_UNREADABLE, AuctionService.cancelListing(a, id), "cannot be cancelled");
		eq(Result.ITEM_UNREADABLE, AuctionService.buyListingAsNpc(id, NpcPayer.treasury()), "cannot be bought by NPC");
		notNull(AuctionStore.findListing(id), "entry kept");
		eq(1, AuctionService.activeCount(a.getUUID()), "counts toward limit");
		e.expiresAt = AuctionService.now() - 1;
		AuctionService.processExpired();
		notNull(AuctionStore.findClaim(id), "moved to claim bin on expiry (not deleted)");
		eq("broken!!", AuctionStore.findClaim(id).item, "raw data unchanged");
		eq(Result.ITEM_UNREADABLE, AuctionService.claim(a, id), "cannot be claimed");
		notNull(AuctionStore.findClaim(id), "stays in claim bin");
		isTrue(AuctionService.ownEntries(a.getUUID()).stream().anyMatch(o -> o.id() == id && o.item().isEmpty()), "unreadable in My Auctions");
	}

	private static void doubleBuy(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 100_00);
		FakePlayer c = player(ctx, "AhThird", 100_00);
		Listing l = list(a, new ItemStack(DIAMOND, 4), 40_00);
		eq(Result.OK, AuctionService.buyListing(b, l.id()), "first buyer");
		eq(Result.NOT_FOUND, AuctionService.buyListing(c, l.id()), "second buyer");
		eq(Result.NOT_FOUND, AuctionService.buyListing(b, l.id()), "first buyer again");
		eq(100_00L, balance(c), "second buyer pays nothing");
		eq(60_00L, balance(b), "first pays once");
		eq(40_00L - AuctionService.taxFor(40_00), balance(a), "seller paid once");
		eq(4, count(b, DIAMOND) + count(c, DIAMOND), "exactly 4 diamonds in circulation");
	}

	private static void notifications(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "AhSeller", 0);
		FakePlayer b = player(ctx, "AhBuyer", 100_00);
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, false);
		int before = PlayerDataService.getPendingMessages(a.getUUID()).size();
		Listing l = list(a, new ItemStack(DIAMOND, 1), 10_00);
		eq(Result.OK, AuctionService.buyListing(b, l.id()), "buy");
		eq(before, PlayerDataService.getPendingMessages(a.getUUID()).size(), "notifications off: no message");
		eq(10_00L - AuctionService.taxFor(10_00), balance(a), "money credited anyway");
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, true);
		Listing l2 = list(a, new ItemStack(DIAMOND, 1), 10_00);
		eq(Result.OK, AuctionService.buyListing(b, l2.id()), "buy 2");
		isTrue(pendingContains(a, "Sold ") && pendingContains(a, "AhBuyer"), "message with buyer name");
	}

	private static void apiEscrow(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		ItemStack stack = new ItemStack(EMERALD, 9);
		eq(Result.INVALID_DURATION, AuctionService.createPlayerListing(a.getUUID(), stack, 1_00, AuctionService.durationMs() + 1).result(), "duration too long");
		eq(Result.NOT_ALLOWED, AuctionService.createPlayerListing(Treasury.ID, stack, 1_00, 0).result(), "system account");
		eq(Result.NO_ITEM, AuctionService.createPlayerListing(a.getUUID(), ItemStack.EMPTY, 1_00, 0).result(), "empty stack");
		CreateResult r = AuctionService.createPlayerListing(a.getUUID(), stack, 5_00, AuctionService.HOUR_MS);
		eq(Result.OK, r.result(), "created");
		stack.setCount(1); // Later changes by the caller must not reach the listing
		eq(9, r.listing().count(), "listing holds a copy");
		eq(9, AuctionService.listing(r.listing().id()).orElseThrow().count(), "also in storage");
		ItemStack fromApi = r.listing().item();
		fromApi.setCount(64);
		eq(9, AuctionService.listing(r.listing().id()).orElseThrow().count(), "item() returns copies");
		check(Math.abs(r.listing().expiresAt() - r.listing().createdAt() - AuctionService.HOUR_MS) == 0, "custom duration");
	}

	private static void storeRepair(TestContext ctx) {
		AuctionStore.Data d = new AuctionStore.Data();
		d.nextId = 1;
		d.listings = null;
		d.claims = null;
		isTrue(AuctionStore.repair(d), "null lists repaired");
		isTrue(d.listings != null && d.claims != null, "lists present");
		Entry e1 = new Entry();
		e1.id = 5;
		e1.seller = UUID.randomUUID();
		Entry e2 = new Entry();
		e2.id = 5;
		e2.seller = UUID.randomUUID();
		e2.item = null;
		Claim c1 = new Claim();
		c1.id = 5;
		c1.reason = null;
		d.listings.add(e1);
		d.listings.add(null);
		d.listings.add(e2);
		d.claims.add(c1);
		isTrue(AuctionStore.repair(d), "duplicate IDs repaired");
		eq(2, d.listings.size(), "nothing but null deleted");
		eq(1, d.claims.size(), "claim stays");
		isTrue(e1.id != e2.id && e1.id != c1.id && e2.id != c1.id, "IDs unique");
		isTrue(d.nextId > Math.max(e1.id, Math.max(e2.id, c1.id)), "nextId past all IDs");
		eq("", e2.item, "null item becomes empty (kept as unreadable)");
		eq("expired", c1.reason, "reason filled in");
		isFalse(AuctionStore.repair(d), "second run changes nothing");
	}

	private static void itemSizeLimit(TestContext ctx) {
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer a = player(ctx, "AhSeller", 0);
		int limit = AuctionConfig.get().maxItemBytes;

		ItemStack big = writableBook(100);
		check(AuctionService.itemBytes(big, limit) > limit, "100 full pages exceed the limit");
		hand(a, big);
		eq(Result.ITEM_TOO_LARGE, AuctionService.listFromHand(a, 1_00).result(), "huge book rejected");
		isTrue(ItemStack.matches(big, handItem(a)), "book stays unchanged in hand");

		ItemStack mid = writableBook(10);
		long midBytes = AuctionService.itemBytes(mid, limit);
		check(midBytes > 0 && midBytes <= limit, "10 pages below limit (" + midBytes + " bytes)");
		ItemStack box = shulkerWith(mid, 27);
		check(AuctionService.itemBytes(box, limit) > limit, "shulker with 27 books above limit");
		hand(a, box);
		eq(Result.ITEM_TOO_LARGE, AuctionService.listFromHand(a, 1_00).result(), "shulker full of books rejected");
		isTrue(ItemStack.matches(box, handItem(a)), "shulker stays unchanged in hand");
		eq(Result.ITEM_TOO_LARGE, AuctionService.createPlayerListing(a.getUUID(), box, 1_00, 0).result(), "API (escrow) rejects");
		eq(Result.ITEM_TOO_LARGE, AuctionService.createNpcListing(big, 1_00, AuctionService.HOUR_MS, "x", SRC).result(), "NPC listing rejects");
		eq(0, AuctionService.activeCount(a.getUUID()), "no listing created");

		Listing ok = list(a, mid, 1_00);
		isTrue(ItemStack.matches(mid, ok.item()), "book below limit is listed");
		eq(0, ctx.drops(a).size(), "nothing dropped");
		ctx.detail("limit " + limit + " bytes, 10-page book " + midBytes + " bytes");
	}

	private static void guiPreviewLight(TestContext ctx) {
		long mib = 1L << 20;
		ItemStack book = writtenBook(100);
		ItemStack box = shulkerWith(book, 27);
		box.set(DataComponents.CUSTOM_NAME, Component.literal("Library"));
		ItemStack original = box.copy();
		check(AuctionService.itemBytes(box, mib) > mib, "original above 1 MiB");

		ItemStack shown = AuctionGui.preview(box, "§7Price: §a$1.00").asStack();
		long bytes = AuctionService.itemBytes(shown, mib);
		check(bytes > 0 && bytes < 8192, "preview small (" + bytes + " bytes)");
		isFalse(AuctionService.holdsItems(shown), "preview without contents");
		isTrue(shown.is(net.minecraft.world.item.Items.SHULKER_BOX), "preview shows the shulker box");
		eq("Library", shown.getHoverName().getString(), "name stays");
		String lore = loreText(shown);
		isTrue(lore.contains("Contents: 27 stacks"), "lore summarizes contents: " + lore);
		isTrue(lore.contains("more"), "lore trims long contents");
		isTrue(lore.contains("Price: $1.00"), "listing lore follows");
		isTrue(ItemStack.matches(original, box), "original unchanged (contents stay for buyer)");

		ItemStack shownBook = AuctionGui.preview(book).asStack();
		WrittenBookContent w = shownBook.get(DataComponents.WRITTEN_BOOK_CONTENT);
		notNull(w, "book data (title) stays");
		eq(0, w.pages().size(), "no pages in preview");
		eq("Diary", w.title().raw(), "title stays");
		isTrue(loreText(shownBook).contains("Book: 100 pages"), "lore names page count");
		eq(100, book.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().size(), "original book unchanged");

		ItemStack quill = writableBook(5);
		ItemStack shownQuill = AuctionGui.preview(quill).asStack();
		isTrue(shownQuill.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY).pages().isEmpty(), "book and quill without pages");
		isTrue(loreText(shownQuill).contains("Book: 5 pages"), "lore names page count (book and quill)");

		ItemStack bundle = new ItemStack(net.minecraft.world.item.Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, Mc.bundle(List.of(new ItemStack(DIAMOND, 16), book.copy())));
		ItemStack shownBundle = AuctionGui.preview(bundle).asStack();
		isFalse(AuctionService.holdsItems(shownBundle), "bundle preview without contents");
		isTrue(loreText(shownBundle).contains("Contents: 2 stacks"), "bundle contents summarized");

		ItemStack sword = fancySword(ctx);
		ItemStack shownSword = AuctionGui.preview(sword).asStack();
		eq(sword.get(DataComponents.ENCHANTMENTS), shownSword.get(DataComponents.ENCHANTMENTS), "enchantments visible");
		eq(sword.getDamageValue(), shownSword.getDamageValue(), "durability visible");
		eq("Excalibur", shownSword.getHoverName().getString(), "name visible");
		ctx.detail("shulker with 27 books: preview " + bytes + " bytes");
	}

	private static ItemStack withLore(Item item, int lines) {
		List<Component> list = new ArrayList<>();
		for (int i = 0; i < lines; i++) list.add(Component.literal("Line " + (i + 1)));
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.LORE, new ItemLore(list));
		return stack;
	}

	private static int loreLines(ItemStack stack) {
		return stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().size();
	}

	private static void loreLimit(TestContext ctx) {
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer a = player(ctx, "AhSeller", 0);
		int max = AuctionConfig.get().maxLoreLines;
		String[] extra = {"", "§8§m                        ", "§7Seller: §fAhSeller", "§7Price: §a$1.00", "§7Expires in: §f1h", "",
			"§eClick to buy"};

		// Items at the vanilla maximum of 256 lines must not break the preview
		ItemStack full = withLore(DIAMOND, ItemLore.MAX_LINES);
		ItemStack shown = AuctionGui.preview(full, extra).asStack();
		isTrue(shown.is(DIAMOND), "preview shows the item (no fallback)");
		eq(ItemLore.MAX_LINES, loreLines(shown), "lore exactly at vanilla limit");
		String text = loreText(shown);
		isTrue(text.startsWith("Line 1\n"), "own lore starts visible");
		isTrue(text.contains("… 8 more lines"), "trim marked: " + text.substring(Math.max(0, text.length() - 200)));
		isTrue(text.contains("Price: $1.00") && text.endsWith("Click to buy\n"), "listing lore complete");
		eq(ItemLore.MAX_LINES, loreLines(full), "original unchanged");

		ItemStack box = shulkerWith(new ItemStack(DIAMOND, 64), 27);
		box.set(DataComponents.LORE, full.get(DataComponents.LORE));
		ItemStack shownBox = AuctionGui.preview(box, extra).asStack();
		isTrue(shownBox.is(net.minecraft.world.item.Items.SHULKER_BOX), "shulker preview without fallback");
		eq(ItemLore.MAX_LINES, loreLines(shownBox), "shulker lore at vanilla limit");
		isTrue(loreText(shownBox).contains("Contents: 27 stacks"), "contents still summarized");

		ItemStack small = withLore(DIAMOND, 10);
		ItemStack shownSmall = AuctionGui.preview(small, extra).asStack();
		eq(10 + extra.length, loreLines(shownSmall), "short lore stays complete");
		isFalse(loreText(shownSmall).contains("more lines"), "short lore not trimmed");

		String[] huge = new String[300];
		java.util.Arrays.fill(huge, "§7x");
		ItemStack fallback = AuctionGui.preview(small, huge).asStack();
		isTrue(fallback.is(net.minecraft.world.item.Items.BARRIER), "fallback instead of exception");
		eq(ItemLore.MAX_LINES, loreLines(fallback), "fallback keeps the vanilla limit");

		ItemStack tooMuch = withLore(DIAMOND, max + 1);
		hand(a, tooMuch);
		eq(Result.ITEM_TOO_LARGE, AuctionService.listFromHand(a, 1_00).result(), (max + 1) + " lore lines rejected");
		isTrue(ItemStack.matches(tooMuch, handItem(a)), "item stays unchanged in hand");
		eq(Result.ITEM_TOO_LARGE, AuctionService.createPlayerListing(a.getUUID(), tooMuch, 1_00, 0).result(), "API (escrow) rejects");
		eq(Result.ITEM_TOO_LARGE, AuctionService.createNpcListing(tooMuch, 1_00, AuctionService.HOUR_MS, "x", SRC).result(), "NPC listing rejects");
		eq(0, AuctionService.activeCount(a.getUUID()), "no listing created");
		eq(0, AuctionService.npcListings(SRC).size(), "no NPC listing created");

		ItemStack atLimit = withLore(DIAMOND, max);
		Listing ok = list(a, atLimit, 1_00);
		isTrue(ItemStack.matches(atLimit, ok.item()), max + " lore lines are listed");
		eq(0, ctx.drops(a).size(), "nothing dropped");
		ctx.detail("maxLoreLines=" + max + ", 256-line preview ok");
	}

	private static void npcBuyBelowSaleFloor(TestContext ctx) {
		richTreasury(ctx);
		FakePlayer a = player(ctx, "AhSeller", 0);
		boolean[] called = {false};
		NpcPayer spy = spyPayer(called);
		long t0 = Treasury.balance();

		// Invariant: whatever market value the caller names, an NPC pays less than an NPC listing of the same stack
		// costs. Otherwise buying from one and selling to the other is free money.
		ItemStack namedDirt = new ItemStack(DIRT, 33);
		namedDirt.set(DataComponents.CUSTOM_NAME, Component.literal("Noble Dirt"));
		List<ItemStack> stacks = List.of(new ItemStack(DIAMOND, 3), mendingBook(ctx), fancySword(ctx), namedDirt);
		for (ItemStack s : stacks) {
			String what = Items.id(s.getItem());
			long floor = AuctionService.npcSaleFloor(a.getUUID(), s);
			Listing l = list(a, s, floor);
			check(AuctionService.npcBuyCap(l, RICH) < floor, "cap below NPC listing floor: " + what);
			eq(Result.PRICE_ABOVE_CAP, AuctionService.buyListingAsNpc(l.id(), RICH, spy), "price at floor: no NPC buy: " + what);
			eq(Result.OK, AuctionService.cancelListing(a, l.id()), "cancel: " + what);
			a.getInventory().clearContent();
		}
		isFalse(called[0], "payer never asked");
		eq(t0, Treasury.balance(), "treasury unchanged");

		ItemStack marked = mendingBook(ctx);
		marked.set(DataComponents.CUSTOM_NAME, Component.literal("AhFloorTest"));
		long testFloor = 50_000_00;
		Hooks.Registration reg = AuctionService.addNpcSaleFloor((buyer, st) ->
			"AhFloorTest".equals(st.getHoverName().getString()) ? testFloor : 0);
		ctx.cleanup(reg::remove);
		long floor = AuctionService.npcSaleFloor(a.getUUID(), marked);
		check(floor >= testFloor, "extra floor applies (" + floor + ")");
		Listing backed = list(a, marked, 10_000_00);
		long cap = AuctionService.npcBuyCap(backed, RICH);
		check(cap < floor, "cap < floor (" + cap + " < " + floor + ")");
		if (PriceGuard.pivotPrice(marked.getItem()) < 0 && !Hooks.flag(Hooks.FLAG_AUCTION_FEVER)) {
			eq(floor - 1, cap, "no shop price: cap = floor - 1");
		}
		long small = 1_000_00;
		long capSmall = AuctionService.npcBuyCap(backed, small);
		check(capSmall <= PriceGuard.maxNpcBuyPrice(a.getUUID(), marked.getItem(), small, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX),
			"small value not raised (" + capSmall + ")");
		long tax = AuctionService.taxFor(backed.price());
		eq(Result.OK, AuctionService.buyListingAsNpc(backed.id(), RICH, NpcPayer.treasury()), "covered value: NPC buys");
		eq(backed.price() - tax, balance(a), "seller gets price minus tax");

		reg.remove();
		long plainFloor = AuctionService.npcSaleFloor(a.getUUID(), marked);
		Listing unbacked = list(a, marked, plainFloor);
		called[0] = false;
		eq(Result.PRICE_ABOVE_CAP, AuctionService.buyListingAsNpc(unbacked.id(), RICH, spy), "uncovered value: no NPC buy");
		isFalse(called[0], "payer not asked");
		isTrue(AuctionService.listing(unbacked.id()).isPresent(), "listing stays");
		ctx.detail("4 stack kinds, covered " + Money.format(cap) + " < " + Money.format(floor));
	}

	private static void npcBuyContentsDamage(TestContext ctx) {
		richTreasury(ctx);
		FakePlayer a = player(ctx, "AhSeller", 0);
		boolean[] called = {false};
		NpcPayer spy = spyPayer(called);
		long t0 = Treasury.balance();

		// The contents would be destroyed with the item
		Listing box = list(a, filledShulker(ctx), 1);
		eq(0L, AuctionService.npcBuyCap(box, RICH), "cap 0 for shulker with contents");
		eq(Result.NOT_NPC_TRADABLE, AuctionService.buyListingAsNpc(box.id(), RICH, spy), "shulker with contents (own value)");
		eq(Result.NOT_NPC_TRADABLE, AuctionService.buyListingAsNpc(box.id(), spy), "shulker with contents (item value)");
		ItemStack bundle = new ItemStack(net.minecraft.world.item.Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, Mc.bundle(List.of(new ItemStack(DIAMOND, 16))));
		Listing bl = list(a, bundle, 1);
		eq(Result.NOT_NPC_TRADABLE, AuctionService.buyListingAsNpc(bl.id(), RICH, spy), "bundle with contents");
		isTrue(AuctionService.listing(box.id()).isPresent() && AuctionService.listing(bl.id()).isPresent(), "listings stay");
		isFalse(AuctionService.holdsItems(new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX)), "empty shulker box is ok");
		isFalse(AuctionService.holdsItems(new ItemStack(net.minecraft.world.item.Items.BUNDLE)), "empty bundle is ok");
		isFalse(AuctionService.holdsItems(new ItemStack(net.minecraft.world.item.Items.PIG_SPAWN_EGG)), "spawn egg (default data) is ok");

		ItemStack worn = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
		worn.setDamageValue(worn.getMaxDamage() - 1);
		Listing wl = list(a, worn, 1);
		eq(Result.NOT_NPC_TRADABLE, AuctionService.buyListingAsNpc(wl.id(), spy), "damaged (item value)");
		ItemStack namedDiamond = new ItemStack(DIAMOND, 1);
		namedDiamond.set(DataComponents.CUSTOM_NAME, Component.literal("Sparkle Stone"));
		Listing nd = list(a, namedDiamond, 1);
		eq(Result.NOT_NPC_TRADABLE, AuctionService.buyListingAsNpc(nd.id(), spy), "named (item value)");

		int max = worn.getMaxDamage();
		eq(1000L, AuctionService.durabilityShare(1000, new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD)), "new means full value");
		ItemStack half = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
		half.setDamageValue(max / 2);
		eq(1000L * (max - max / 2) / max, AuctionService.durabilityShare(1000, half), "half worn");
		eq(1000L / max, AuctionService.durabilityShare(1000, worn), "almost broken");
		ItemStack unbreakable = worn.copy();
		unbreakable.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		eq(1000L, AuctionService.durabilityShare(1000, unbreakable), "unbreakable means full value");
		eq(1000L, AuctionService.durabilityShare(1000, new ItemStack(DIAMOND)), "not damageable means full value");

		Listing fresh = list(a, new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD), 1);
		long capFresh = AuctionService.npcBuyCap(fresh, RICH);
		long capWorn = AuctionService.npcBuyCap(wl, RICH);
		check(capWorn <= capFresh, "worn <= new (" + capWorn + " ≤ " + capFresh + ")");
		if (capFresh > 1) check(capWorn < capFresh, "worn < new (" + capWorn + " < " + capFresh + ")");
		check(capWorn < AuctionService.npcSaleFloor(a.getUUID(), worn), "worn below NPC listing floor");

		isFalse(called[0], "payer never asked");
		eq(t0, Treasury.balance(), "treasury unchanged");
		eq(0L, balance(a), "seller unchanged");
		ctx.detail("shulker/bundle rejected, durability " + capWorn + " ≤ " + capFresh);
	}

	/** Only the exact stack held at click time (slot, item, count) may be listed on confirm. */
	private static void sellButtonFlow(TestContext ctx) {
		FakePlayer a = player(ctx, "AhSeller", 0);
		UUID id = a.getUUID();
		AuctionConfig cfg = AuctionConfig.get();
		Inventory inv = a.getInventory();
		int slot = inv.getSelectedSlot();
		int otherSlot = (slot + 1) % 9;

		inv.clearContent();
		isTrue(AuctionSellGui.held(a) == null, "empty hand: nothing held");
		eq(Result.NO_ITEM, AuctionSellGui.precheck(a), "empty hand: precheck");
		eq("Hold the item you want to sell in your main hand.", Result.NO_ITEM.message(), "hint on empty hand");
		hand(a, new ItemStack(net.minecraft.world.item.Items.BARRIER));
		eq(Result.INVALID_ITEM, AuctionSellGui.precheck(a), "blocked item rejected at click");
		ItemStack diamonds = new ItemStack(DIAMOND, 16);
		hand(a, diamonds);
		eq(Result.OK, AuctionSellGui.precheck(a), "16 diamonds: precheck ok");
		isTrue(ItemStack.matches(diamonds, handItem(a)), "precheck takes nothing");
		a.setGameMode(GameType.CREATIVE);
		try {
			eq(Result.CREATIVE, AuctionSellGui.precheck(a), "creative rejected at click");
		} finally {
			a.setGameMode(GameType.SURVIVAL);
		}

		long market = Services.marketValue(DIAMOND).orElse(0);
		check(market > 0, "diamond has a market value");
		long suggested = AuctionSellConfirm.suggestedTotal(id, diamonds, cfg.maxPrice);
		check(suggested >= market * 16, "suggestion >= market value x 16 (" + Money.format(suggested) + ")");
		isFalse(AuctionSellConfirm.quote(id, diamonds, suggested).warn(), "suggestion without warning");
		eq(suggested, Money.parse(Money.commandAmount(suggested)).orElse(-1), "suggestion readable in price field");
		eq(0L, AuctionSellConfirm.suggestedTotal(id, filledShulker(ctx), cfg.maxPrice), "no suggestion for items with contents");
		eq(0L, AuctionSellConfirm.suggestedTotal(id, ItemStack.EMPTY, cfg.maxPrice), "no suggestion without item");
		check(AuctionSellConfirm.suggestedTotal(id, new ItemStack(DIAMOND, 64), 5_00) == 5_00, "suggestion at most maxPrice");

		eq(1000_00L, AuctionSellGui.parsePrice("1000").orElse(-1), "1000");
		eq(2500_00L, AuctionSellGui.parsePrice("2.5k").orElse(-1), "2.5k");
		eq(1_000_000_00L, AuctionSellGui.parsePrice("1m").orElse(-1), "1m");
		eq(1500_00L, AuctionSellGui.parsePrice(" $1,500 ").orElse(-1), "$1,500");
		eq(12_50L, AuctionSellGui.parsePrice("12.50").orElse(-1), "12.50");
		for (String bad : new String[] {"", "abc", "0", "-5", "1.000", "1.234.5"}) {
			isTrue(AuctionSellGui.parsePrice(bad).isEmpty(), "invalid: \"" + bad + "\"");
		}

		AuctionSellConfirm.Quote cheap = AuctionSellConfirm.quote(id, diamonds, 100_00);
		isFalse(AuctionSellConfirm.warnings(cheap).isEmpty(), "16 diamonds for $100: warning");
		isTrue(AuctionSellConfirm.warnings(cheap).stream().anyMatch(w -> w.contains("below market value")), "warning names market value");
		eq(1600_00L, AuctionSellConfirm.eachInstead(cheap, cfg.maxPrice), "$100 x 16 suggested");
		eq(0L, AuctionSellConfirm.eachInstead(cheap, 1000_00), "suggestion above maxPrice dropped");
		AuctionSellConfirm.Quote fair = AuctionSellConfirm.quote(id, diamonds, suggested);
		isTrue(AuctionSellConfirm.warnings(fair).isEmpty(), "market price: no warning");
		eq(0L, AuctionSellConfirm.eachInstead(fair, cfg.maxPrice), "market price: no unit price hint");
		eq(0L, AuctionSellConfirm.eachInstead(AuctionSellConfirm.quote(id, new ItemStack(DIAMOND), 1), cfg.maxPrice), "single item: no hint");

		AuctionSellGui.Held held = AuctionSellGui.held(a);
		notNull(held, "16 diamonds held");
		eq(slot, held.slot(), "slot remembered");
		int before = AuctionService.activeCount(id);
		hand(a, new ItemStack(DIRT, 16));
		AuctionSellGui.Outcome o = AuctionSellGui.list(a, held, 100_00);
		isTrue(o.handChanged() && !o.ok(), "other item: aborted");
		eq(16, count(a, DIRT), "dirt stays in hand");
		hand(a, new ItemStack(DIAMOND, 17));
		isTrue(AuctionSellGui.list(a, held, 100_00).handChanged(), "other count: aborted");
		eq(17, count(a, DIAMOND), "17 diamonds stay");
		ItemStack named = diamonds.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Glitter"));
		hand(a, named);
		isTrue(AuctionSellGui.list(a, held, 100_00).handChanged(), "other components: aborted");
		inv.setItem(slot, ItemStack.EMPTY);
		isTrue(AuctionSellGui.list(a, held, 100_00).handChanged(), "hand emptied: aborted");
		inv.setItem(otherSlot, diamonds.copy());
		inv.setSelectedSlot(otherSlot);
		try {
			isTrue(AuctionSellGui.list(a, held, 100_00).handChanged(), "other hotbar slot (same stack): aborted");
			eq(16, count(a, DIAMOND), "diamonds in other slot stay");
		} finally {
			inv.setSelectedSlot(slot);
		}
		eq(before, AuctionService.activeCount(id), "no listing created");
		isTrue(AuctionSellGui.list(a, null, 100_00).handChanged(), "never list without a stored state");

		inv.clearContent();
		hand(a, diamonds);
		AuctionSellGui.Outcome tooHigh = AuctionSellGui.list(a, held, cfg.maxPrice + 1);
		isFalse(tooHigh.handChanged(), "hand matches");
		eq(Result.INVALID_PRICE, tooHigh.result().result(), "price above maximum, as with /ah sell");
		isTrue(ItemStack.matches(diamonds, handItem(a)), "item stays (price)");

		AuctionSellGui.Outcome ok = AuctionSellGui.list(a, held, 100_00);
		isTrue(ok.ok(), "listed");
		Listing l = ok.result().listing();
		eq(16, l.count(), "whole stack");
		isTrue(ItemStack.matches(diamonds, l.item()), "exactly the held item");
		eq(100_00L, l.price(), "price is the total");
		isTrue(handItem(a).isEmpty(), "hand empty");
		eq(0, count(a, DIAMOND), "no diamonds left in inventory");
		eq(before + 1, AuctionService.activeCount(id), "exactly one more listing");
		isTrue(AuctionSellGui.list(a, held, 100_00).handChanged(), "second confirm: nothing");
		eq(before + 1, AuctionService.activeCount(id), "still one listing");

		for (int i = AuctionService.activeCount(id); i < cfg.maxListings; i++) list(a, new ItemStack(DIRT, 1 + i), 1_00);
		hand(a, diamonds);
		AuctionSellGui.Held again = AuctionSellGui.held(a);
		AuctionSellGui.Outcome limit = AuctionSellGui.list(a, again, 100_00);
		eq(Result.LIMIT_REACHED, limit.result() == null ? null : limit.result().result(), "limit: LIMIT_REACHED");
		isTrue(ItemStack.matches(diamonds, handItem(a)), "item stays (limit)");
		eq(Result.LIMIT_REACHED, AuctionSellGui.precheck(a), "limit detected at click");
		ctx.detail("suggestion " + Money.format(suggested) + " for 16 diamonds; swap/count/name/slot/empty: aborted");
	}

	private static void aliases(TestContext ctx) {
		var root = ctx.server().getCommands().getDispatcher().getRoot();
		var ah = root.getChild("ah");
		notNull(ah, "/ah registered");
		java.util.Set<String> subs = new java.util.TreeSet<>();
		ah.getChildren().forEach(n -> subs.add(n.getName()));
		check(subs.containsAll(List.of("sell", "search", "my", "admin")), "/ah has sell/search/my/admin (" + subs + ")");
		for (String name : List.of("auction")) {
			var node = root.getChild(name);
			notNull(node, "/" + name + " registered");
			isTrue(node.getCommand() != null, "/" + name + " alone opens the auction house");
			java.util.Set<String> mine = new java.util.TreeSet<>();
			node.getChildren().forEach(n -> mine.add(n.getName()));
			eq(subs, mine, "/" + name + " has the same subcommands as /ah");
			var sell = node.getChild("sell");
			isTrue(sell != null && sell.getChild(AuctionCommands.ARG_PRICE) != null, "/" + name + " sell <total_price>");
		}
		eq(List.of("ah", "auction"), AuctionCommands.NAMES, "names");
		ctx.detail("/ah = /auction (" + subs + ")");
	}

	/**
	 * Bargain floor: below the normal NPC floor, but per item never below the buyer's /sell plus 1 cent (rechecked
	 * at purchase with their bonuses) and for shop items never below pivot + 1. A provider without a bargain floor
	 * relaxes nothing.
	 */
	private static void npcBargainFloor(TestContext ctx) {
		richTreasury(ctx);
		ctx.cleanup(() -> AuctionService.removeNpcListings(SRC));
		FakePlayer b = player(ctx, "AhBargain", 0);
		UUID buyer = b.getUUID();
		Hooks.Registration boni = Hooks.addSellMultiplier(SRC, "selftest bonus", (player, item) -> buyer.equals(player) ? 1.68 : 1.0);
		ctx.cleanup(boni::remove);
		ItemStack diamonds = new ItemStack(DIAMOND, 4);
		CreateResult r = AuctionService.createNpcListing(diamonds, 1, AuctionService.HOUR_MS, "§e[Market] Trader Harry", SRC,
			new AuctionService.NpcOffer(true, "§a★ Bargain!"));
		eq(Result.OK, r.result(), "bargain created");
		Listing l = r.listing();
		isTrue(l.bargain(), "Flag");
		eq("§a★ Bargain!", l.note(), "note");
		long normal = AuctionService.npcSaleFloor(null, diamonds);
		long sell = PriceGuard.effectiveSellPrice(new UUID(0, 0), DIAMOND).orElse(0);
		check(l.price() >= 4 * (sell + 1), "never below /sell + 1 cent (" + l.price() + " ≥ " + 4 * (sell + 1) + ")");
		boolean relaxed = l.price() < normal;
		if (!AuctionService.npcSaleFloorProviders()) check(!relaxed, "no relaxation without provider");
		isTrue(AuctionGui.listingLore(l).contains("§a★ Bargain!"), "note in lore");
		long buyerSell = PriceGuard.effectiveSellPrice(buyer, DIAMOND).orElse(0);
		check(buyerSell > sell, "bonuses apply (" + buyerSell + ")");
		long buyerFloor = AuctionService.npcBargainFloor(buyer, diamonds);
		check(buyerFloor >= 4 * (buyerSell + 1), "buyer's floor >= their /sell + 1 cent");
		EconomyService.set(buyer, RICH);
		long price = l.price();
		Result res = AuctionService.buyListing(b, l.id(), price);
		if (price < buyerFloor) {
			eq(Result.PRICE_CHANGED, res, "too cheap for this buyer: raised");
			price = AuctionService.listing(l.id()).orElseThrow().price();
			check(price >= buyerFloor, "raised to their floor");
			res = AuctionService.buyListing(b, l.id(), price);
		}
		eq(Result.OK, res, "bought");
		check(price > 4 * buyerSell, "/sell pays the buyer less than they paid (" + price + " > " + 4 * buyerSell + ")");
		eq(4, count(b, DIAMOND), "diamonds delivered");
		isTrue(Items.count(b, s -> s.is(DIAMOND) && Items.isPristine(s)) == 4, "plain diamonds (pristine, no marker)");
		Item pearl = net.minecraft.world.item.Items.ENDER_PEARL;
		long pivot = PriceGuard.pivotPrice(pearl);
		if (pivot >= 0) {
			Listing pl = AuctionService.createNpcListing(new ItemStack(pearl, 4), 1, AuctionService.HOUR_MS, "x", SRC,
				new AuctionService.NpcOffer(true, "")).listing();
			check(pl.price() >= 4 * (pivot + 1), "shop item: bargain >= pivot + 1 (" + pl.price() + ")");
		}
		Hooks.Registration reg = AuctionService.addNpcSaleFloor((who, stack) -> 777_777);
		try {
			check(AuctionService.npcBargainFloor(null, new ItemStack(DIRT, 1)) >= 777_777, "no relaxation without own bargain floor");
		} finally {
			reg.remove();
		}
		AuctionStore.Data d = new AuctionStore.Data();
		Entry e = new Entry();
		e.id = 5;
		e.npc = true;
		e.bargain = true;
		e.note = "§a★";
		e.item = "x";
		d.listings.add(e);
		Entry pe = new Entry();
		pe.id = 6;
		pe.seller = buyer;
		pe.bargain = true;
		pe.note = "wrong";
		pe.item = "x";
		d.listings.add(pe);
		AuctionStore.Data back = DataStore.gson().fromJson(DataStore.gson().toJson(d), AuctionStore.Data.class);
		AuctionStore.repair(back);
		isTrue(back.listings.get(0).bargain && "§a★".equals(back.listings.get(0).note), "bargain saved");
		isFalse(back.listings.get(1).bargain || !back.listings.get(1).note.isEmpty(), "player listing never a bargain");
		AuctionStore.Data legacy = DataStore.gson().fromJson("{\"listings\":[{\"id\":9,\"npc\":true,\"item\":\"x\"}]}", AuctionStore.Data.class);
		AuctionStore.repair(legacy);
		isTrue(!legacy.listings.get(0).bargain && legacy.listings.get(0).note.isEmpty(), "old file: normal listing");
		eq(AuctionService.MAX_NOTE_LENGTH, new AuctionService.NpcOffer(true, "x".repeat(200)).note().length(), "note trimmed");
		eq("a b", new AuctionService.NpcOffer(true, "a\nb").note(), "note single line");
		ctx.detail("diamond bargain " + Money.format(l.price()) + " (normal from " + Money.format(normal) + "), buyer with bonuses "
			+ Money.format(price));
	}

	private static void configMigration() {
		var gson = DataStore.gson();
		com.google.gson.JsonObject old = gson.toJsonTree(new AuctionConfig()).getAsJsonObject();
		old.remove("defaultsVersion");
		old.addProperty("maxNpcListings", 30);
		AuctionConfig a = gson.fromJson(old, AuctionConfig.class);
		AuctionConfig.validate(a);
		eq(new AuctionConfig().maxNpcListings, a.maxNpcListings, "30 becomes new default");
		eq(AuctionConfig.DEFAULTS_VERSION, a.defaultsVersion, "version set");
		check(a.maxNpcListings >= 80, "room for 80 NPC listings");
		old.addProperty("maxNpcListings", 40);
		AuctionConfig b = gson.fromJson(old, AuctionConfig.class);
		AuctionConfig.validate(b);
		eq(40, b.maxNpcListings, "custom value stays");
		AuctionConfig c = gson.fromJson(old, AuctionConfig.class);
		c.defaultsVersion = AuctionConfig.DEFAULTS_VERSION;
		c.maxNpcListings = 30;
		AuctionConfig.validate(c);
		eq(30, c.maxNpcListings, "values set after migration stay");
	}
}
