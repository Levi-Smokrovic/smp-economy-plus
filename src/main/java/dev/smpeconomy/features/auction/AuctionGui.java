package dev.smpeconomy.features.auction;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.features.auction.AuctionService.Listing;
import dev.smpeconomy.features.auction.AuctionService.OwnEntry;
import dev.smpeconomy.features.auction.AuctionService.Result;
import dev.smpeconomy.features.auction.AuctionService.Sort;

/**
 * Display-only chest GUIs with no real slots. Every action rechecks everything when it runs, in {@link
 * AuctionService}.
 */
final class AuctionGui {
	static final String TITLE = "§8Auction House";
	static final String MY_TITLE = "§8My Auctions";
	static final String BUY_TITLE = "§8Confirm Purchase";
	static final String CANCEL_TITLE = "§8Cancel Listing?";

	static final int SLOT_SORT = 45;
	static final int SLOT_FILTER = 46;
	static final int SLOT_SEARCH = 47;
	static final int SLOT_MY = 51;
	static final int SLOT_SELL = AuctionSellGui.SLOT;
	static final int SLOT_CLOSE = 53;
	static final int SLOT_BACK = 45;
	static final int SLOT_CLAIM_ALL = 46;
	static final int SLOT_INFO = 47;

	static final class State {
		Sort sort = Sort.NEWEST;
		AuctionService.Filter filter = AuctionService.Filter.ALL;
		String query = "";
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private AuctionGui() {}

	static State state(ServerPlayer player) {
		return STATES.computeIfAbsent(player.getUUID(), k -> new State());
	}

	static void openBrowse(ServerPlayer player, int page) {
		if (!Gui.canOpen(player)) return;
		State st = state(player);
		SimpleGui gui = Gui.chest(player, 6, TITLE + (st.query.isEmpty() ? "" : " §8» §7" + shorten(st.query, 18)));
		List<Gui.Pager<Listing>> holder = new ArrayList<>(1);
		Gui.Pager<Listing> pager = new Gui.Pager<>(gui, () -> AuctionService.browse(st.query, st.sort, st.filter),
			l -> renderListing(player, l, holder));
		holder.add(pager);
		pager.withMenuButton();
		renderBrowseControls(player, gui, st, pager);
		pager.show(page);
		gui.open();
	}

	private static void renderBrowseControls(ServerPlayer player, SimpleGui gui, State st, Gui.Pager<Listing> pager) {
		List<String> sortLore = new ArrayList<>();
		sortLore.add("");
		for (Sort s : Sort.values()) sortLore.add((s == st.sort ? "§a» " : "§7  ") + s.label);
		sortLore.add("");
		sortLore.add("§eClick to switch");
		gui.setSlot(SLOT_SORT, Gui.button(Items.HOPPER, "§eSort: §f" + st.sort.label, () -> {
			st.sort = st.sort.next();
			pager.show(0);
			renderBrowseControls(player, gui, st, pager);
		}, sortLore.toArray(String[]::new)));

		List<String> filterLore = new ArrayList<>();
		filterLore.add("");
		for (AuctionService.Filter f : AuctionService.Filter.values()) filterLore.add((f == st.filter ? "§a» " : "§7  ") + f.label);
		filterLore.add("§8NPC listings are marked §e[Market]");
		filterLore.add("§8– some Traders miscalculate (§a★ Bargain§8)");
		filterLore.add("");
		filterLore.add("§eClick to switch");
		Item filterIcon = switch (st.filter) {
			case PLAYERS -> Items.PLAYER_HEAD;
			case NPC -> Items.GOLD_NUGGET;
			default -> Items.EMERALD;
		};
		gui.setSlot(SLOT_FILTER, Gui.button(filterIcon, "§eShow: §f" + st.filter.label, () -> {
				st.filter = st.filter.next();
				pager.show(0);
				renderBrowseControls(player, gui, st, pager);
			}, filterLore.toArray(String[]::new)));

		gui.setSlot(SLOT_SEARCH, Gui.button(Items.OAK_SIGN, st.query.isEmpty() ? "§eSearch" : "§eSearch: §f" + shorten(st.query, 24), type -> {
			if (type.isRight) {
				st.query = "";
				openBrowse(player, 0);
				return;
			}
			Gui.textInput(player, "§8Search the Auction House", st.query.isEmpty() ? "" : st.query, text -> {
				st.query = text == null ? "" : text;
				openBrowse(player, 0);
			}, () -> openBrowse(player, pager.page()));
		}, "", "§7Search by item name or ID", "§7(e.g. §fdiamond§7, §fnetherite§7, §fmending§7)", "",
			"§eLeft-click: §7enter a search term", "§eRight-click: §7clear the search"));

		UUID id = player.getUUID();
		gui.setSlot(SLOT_MY, Gui.button(Items.CHEST, "§eMy Auctions", () -> openMy(player, 0),
			"", "§7Active listings: §f" + AuctionService.activeCount(id) + "§7/§f" + AuctionConfig.get().maxListings,
			"§7Ready to claim: §f" + AuctionService.claimCount(id), "§6Balance: " + Msg.moneyStr(EconomyService.balance(id)),
			"", "§eClick to open"));

		gui.setSlot(SLOT_SELL, AuctionSellGui.button(player, () -> openBrowse(player, pager.page())));

		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
	}

	private static GuiElementBuilder renderListing(ServerPlayer player, Listing l, List<Gui.Pager<Listing>> pager) {
		boolean own = !l.npc() && player.getUUID().equals(l.seller());
		List<String> lore = listingLore(l);
		lore.add("");
		lore.add(own ? "§8Your listing – cancel it in »My Auctions«" : "§eClick to buy");
		GuiElementBuilder b = preview(l.item(), lore.toArray(String[]::new));
		long id = l.id();
		b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			Gui.click(player);
			onListingClick(player, id, pager.get(0));
		});
		return b;
	}

	static List<String> listingLore(Listing l) {
		List<String> lore = new ArrayList<>();
		lore.add("");
		lore.add("§8§m                        ");
		if (l.npc()) {
			lore.add("§7Seller: " + (l.sellerName().isEmpty() ? "§e[Market] Trader" : l.sellerName()));
			lore.add("§8NPC listing (money goes to the Treasury)");
			if (!l.note().isEmpty()) lore.add(l.note());
		} else {
			lore.add("§7Seller: §f" + l.sellerName());
		}
		lore.add("§7Price: " + Msg.moneyStr(l.price()) + (l.count() > 1 ? " §8(" + Money.format(l.unitPrice()) + " each)" : ""));
		if (l.npc()) {
			ItemStack stack = l.item();
			long market = dev.smpeconomy.core.Items.isPristine(stack) ? Services.marketValue(stack.getItem()).orElse(0) : 0;
			if (market > 0) lore.add("§8Market value: " + Money.format(market) + " each");
		}
		lore.add("§7Expires in: §f" + AuctionService.remaining(l.expiresAt()));
		return lore;
	}

	private static void onListingClick(ServerPlayer player, long id, Gui.Pager<Listing> pager) {
		Optional<Listing> current = AuctionService.listing(id);
		if (current.isEmpty()) {
			Msg.error(player, Result.NOT_FOUND.message());
			Gui.failSound(player);
			pager.refresh();
			return;
		}
		Listing l = current.get();
		if (!l.npc() && player.getUUID().equals(l.seller())) {
			Msg.error(player, "That's your own listing – you can cancel it in »My Auctions«.");
			Gui.failSound(player);
			return;
		}
		int page = pager.page();
		long balance = EconomyService.balance(player.getUUID());
		List<String> lore = listingLore(l);
		lore.add("§7Your balance: " + Msg.moneyStr(balance));
		if (balance < l.price()) lore.add("§cYou don't have enough money.");
		long price = l.price();
		Gui.confirm(player, BUY_TITLE, preview(l.item(), lore.toArray(String[]::new)), () -> {
			Result r = AuctionService.buyListing(player, id, price);
			if (r == Result.OK) {
				Msg.send(player, Msg.prefixed(Msg.legacy("§aBought ").append(AuctionService.itemText(l.item()))
					.append(Msg.legacy(" §afor " + Msg.moneyStr(price) + "§a."))));
				Gui.successSound(player);
			} else {
				Msg.error(player, r.message());
				Gui.failSound(player);
			}
			openBrowse(player, page);
		}, () -> openBrowse(player, page));
	}

	static void openMy(ServerPlayer player, int page) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, MY_TITLE);
		UUID id = player.getUUID();
		List<Gui.Pager<OwnEntry>> holder = new ArrayList<>(1);
		Gui.Pager<OwnEntry> pager = new Gui.Pager<>(gui, () -> AuctionService.ownEntries(id), e -> renderOwn(player, e, holder));
		holder.add(pager);
		renderMyControls(player, gui, pager);
		pager.show(page);
		gui.open();
	}

	private static void renderMyControls(ServerPlayer player, SimpleGui gui, Gui.Pager<OwnEntry> pager) {
		UUID id = player.getUUID();
		gui.setSlot(SLOT_BACK, Gui.backButton(() -> openBrowse(player, 0)));
		int claims = AuctionService.claimCount(id);
		gui.setSlot(SLOT_CLAIM_ALL, Gui.button(Items.HOPPER, "§eClaim All", () -> {
			int[] r = AuctionService.claimAll(player);
			if (r[0] > 0) {
				Msg.success(player, "Claimed " + r[0] + " item stack" + (r[0] == 1 ? "" : "s") + "." + (r[1] > 0 ? " §7(" + r[1] + " didn't fit in your inventory)" : ""));
				Gui.successSound(player);
			} else if (r[1] > 0) {
				Msg.error(player, Result.NO_SPACE.message());
				Gui.failSound(player);
			} else {
				Msg.info(player, "Nothing to claim.");
			}
			pager.refresh();
			renderMyControls(player, gui, pager);
		}, "", "§7Ready to claim: §f" + claims, "§8Only what fits in your inventory", "", "§eClick to claim"));
		gui.setSlot(SLOT_INFO, Gui.item(Items.BOOK, "§eInfo",
			"", "§7Active listings: §f" + AuctionService.activeCount(id) + "§7/§f" + AuctionConfig.get().maxListings,
			"§7Ready to claim: §f" + claims, "", "§7Sell an item: §fhold it, click §a§lSell item",
			"§8(or /ah sell <total price> for the whole stack in your hand)", "§7Duration: §f" + AuctionConfig.get().durationHours + "h",
			"§7Tax on sale: §f" + percent(AuctionConfig.get().taxPercent)));
		gui.setSlot(SLOT_SELL, AuctionSellGui.button(player, () -> openMy(player, pager.page())));
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
	}

	private static GuiElementBuilder renderOwn(ServerPlayer player, OwnEntry e, List<Gui.Pager<OwnEntry>> pager) {
		GuiElementBuilder b;
		if (e.item().isEmpty()) {
			b = Gui.item(Items.BARRIER, "§cItem unreadable", "§7Please report this to an admin (#" + e.id() + ")");
			return b;
		}
		if (e.claim()) {
			String why = switch (e.reason()) {
				case "cancelled" -> "§cCancelled";
				case "admin" -> "§cRemoved by an admin";
				default -> "§cExpired";
			};
			b = preview(e.item(), "", "§8§m                        ", why, "§7Price was: " + Msg.moneyStr(e.price()), "", "§eClick to claim");
			long id = e.id();
			b.setCallback((index, type, action, g) -> {
				if (!Gui.acceptClick(player, index, type)) return;
				Gui.click(player);
				Result r = AuctionService.claim(player, id);
				if (r == Result.OK) {
					Msg.success(player, "Claimed.");
					Gui.successSound(player);
				} else {
					Msg.error(player, r.message());
					Gui.failSound(player);
				}
				openMy(player, pager.get(0).page());
			});
			return b;
		}
		boolean expired = e.expiresAt() <= AuctionService.now();
		b = preview(e.item(), "", "§8§m                        ", "§aActive", "§7Price: " + Msg.moneyStr(e.price()),
			"§7Expires in: §f" + (expired ? "soon" : AuctionService.remaining(e.expiresAt())), "", "§eClick to cancel");
		long id = e.id();
		b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			Gui.click(player);
			int page = pager.get(0).page();
			Gui.confirm(player, CANCEL_TITLE, preview(e.item(), "", "§7Price: " + Msg.moneyStr(e.price()), "§7The item goes back to your inventory."), () -> {
				Result r = AuctionService.cancelListing(player, id);
				if (r == Result.OK) {
					Msg.success(player, "Listing cancelled – the item is back in your inventory.");
					Gui.successSound(player);
				} else if (r == Result.STORED) {
					Msg.info(player, r.message());
				} else {
					Msg.error(player, r.message());
					Gui.failSound(player);
				}
				openMy(player, page);
			}, () -> openMy(player, page));
		});
		return b;
	}

	static final int PREVIEW_CONTENT_LINES = 6;

	/**
	 * Display copy without heavy data: shulker and bundle contents and book pages become lore. Otherwise every
	 * component of every listing ends up in the window packet, and a few listings full of books could exceed the
	 * vanilla limit and disconnect every viewer. The real item is what gets bought or claimed.
	 */
	static GuiElementBuilder preview(ItemStack stack, String... lore) {
		try {
			ItemStack copy = stack.copy();
			List<Component> summary = lighten(copy);
			capLore(copy, summary.size() + lore.length);
			GuiElementBuilder b = Gui.display(copy);
			for (Component line : summary) b.addLoreLine(line);
			return Gui.lore(b, lore);
		} catch (RuntimeException e) {
			// A broken listing must not make the page unusable for everyone
			SmpEconomy.LOG.warn("[SMPEco] Auction: preview failed for {}: {}", dev.smpeconomy.core.Items.id(stack.getItem()), e.toString());
			String[] safe = lore.length <= ItemLore.MAX_LINES ? lore : Arrays.copyOf(lore, ItemLore.MAX_LINES);
			return Gui.item(Items.BARRIER, "§cPreview unavailable", safe);
		}
	}

	/**
	 * Trims own lore so that with {@code extra} appended lines it fits {@link ItemLore#MAX_LINES}; {@link
	 * ItemLore} throws above that.
	 */
	static void capLore(ItemStack copy, int extra) {
		ItemLore own = copy.get(DataComponents.LORE);
		if (own == null) return;
		int total = own.lines().size();
		if (total + extra <= ItemLore.MAX_LINES) return;
		int keep = Math.max(0, ItemLore.MAX_LINES - extra - 1);
		List<Component> lines = new ArrayList<>(own.lines().subList(0, Math.min(keep, total)));
		lines.add(Msg.legacy("§8… " + (total - lines.size()) + " more lines").withStyle(s -> s.withItalic(false)));
		copy.set(DataComponents.LORE, new ItemLore(lines));
	}

	/** Strips contents and book pages from {@code copy} and returns lore lines summarizing them. */
	static List<Component> lighten(ItemStack copy) {
		List<Component> lines = new ArrayList<>();
		List<ItemStack> contents = new ArrayList<>();
		ItemContainerContents container = copy.get(DataComponents.CONTAINER);
		if (container != null) {
			contents.addAll(Mc.containerItems(container));
			reset(copy, DataComponents.CONTAINER);
		}
		BundleContents bundle = copy.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			contents.addAll(Mc.bundleItems(bundle));
			reset(copy, DataComponents.BUNDLE_CONTENTS);
		}
		if (!contents.isEmpty()) {
			int n = contents.size();
			lines.add(Msg.legacy("§7Contents: §f" + n + (n == 1 ? " stack" : " stacks")));
			for (int i = 0; i < n && i < PREVIEW_CONTENT_LINES; i++) {
				ItemStack s = contents.get(i);
				lines.add(Msg.legacy("§8• §f" + s.getCount() + "x ").append(Msg.itemName(s).withStyle(ChatFormatting.WHITE)));
			}
			if (n > PREVIEW_CONTENT_LINES) lines.add(Msg.legacy("§8… and " + (n - PREVIEW_CONTENT_LINES) + " more"));
		}
		WrittenBookContent written = copy.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (written != null && !written.pages().isEmpty()) {
			// Title, author and generation stay visible
			copy.set(DataComponents.WRITTEN_BOOK_CONTENT,
				new WrittenBookContent(written.title(), written.author(), written.generation(), List.of(), written.resolved()));
			lines.add(Msg.legacy("§7Book: §f" + pages(written.pages().size())));
		}
		WritableBookContent writable = copy.get(DataComponents.WRITABLE_BOOK_CONTENT);
		if (writable != null && !writable.pages().isEmpty()) {
			reset(copy, DataComponents.WRITABLE_BOOK_CONTENT);
			lines.add(Msg.legacy("§7Book: §f" + pages(writable.pages().size())));
		}
		return lines;
	}

	private static String pages(int n) {
		return n + (n == 1 ? " page" : " pages");
	}

	/** Resets a component to the item's default, or removes it if the item has none. */
	private static <T> void reset(ItemStack stack, DataComponentType<T> type) {
		T prototype = stack.getPrototype().get(type);
		if (prototype != null) stack.set(type, prototype);
		else stack.remove(type);
	}

	static String percent(double pct) {
		return (pct == Math.rint(pct) ? String.valueOf((long) pct) : String.valueOf(pct)) + "%";
	}

	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
