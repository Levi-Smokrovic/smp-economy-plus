package dev.smpeconomy.features.bounty;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;

/**
 * Bounty leaderboard (6 rows, pager). Click a head, enter an amount on the anvil, confirm. {@link #confirm}
 * is the same dialog {@code /bounty <player> <amount>} uses. Bottom row: info (45), place bounty (46), bounty
 * on you (47), paging (48-50; 49 is the main menu button when /menu exists), recent hunts (51), your
 * contributions (52), close (53). Display-only, no real slots.
 */
final class BountyGui {
	static final String TITLE = "§4§lBounties";
	static final String TITLE_CONFIRM = "§8Confirm bounty";
	static final int SLOT_INFO = 45;
	static final int SLOT_SET = 46;
	static final int SLOT_OWN = 47;
	static final int SLOT_HISTORY = 51;
	static final int SLOT_MINE = 52;
	static final int SLOT_CLOSE = 53;
	static final int SLOT_EMPTY = 22;
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int LORE_LIST_MAX = 8;

	/** Entry with 1-based rank for the pager. */
	private record Row(int rank, BountyLedger.Entry entry) {}

	private BountyGui() {}

	static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, TITLE);
		UUID self = player.getUUID();
		gui.setSlot(SLOT_INFO, info());
		gui.setSlot(SLOT_SET, Gui.button(Items.NAME_TAG, "§ePlace a bounty", () -> askName(player),
			"§7Enter a player name,", "§7then the amount.", "", "§8Or: §e/bounty <player> <amount>"));
		gui.setSlot(SLOT_OWN, ownHead(player));
		gui.setSlot(SLOT_HISTORY, history());
		gui.setSlot(SLOT_MINE, mine(self));
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.Pager<Row> pager = new Gui.Pager<>(gui, BountyGui::rows, row -> entry(player, row)).withMenuButton();
		pager.show(0);
		if (Bounties.LIVE.top().isEmpty()) {
			gui.setSlot(SLOT_EMPTY, Gui.item(Items.PAPER, "§7No bounties", "§8Place one with §e/bounty <player> <amount>"));
		}
		gui.open();
	}

	private static List<Row> rows() {
		List<BountyLedger.Entry> top = Bounties.LIVE.top();
		List<Row> rows = new ArrayList<>(top.size());
		for (int i = 0; i < top.size(); i++) rows.add(new Row(i + 1, top.get(i)));
		return rows;
	}

	private static GuiElementBuilder entry(ServerPlayer viewer, Row row) {
		BountyLedger.Entry e = row.entry();
		String name = EconomyService.nameOrUnknown(e.target());
		boolean self = e.target().equals(viewer.getUUID());
		MinecraftServer server = viewer.level().getServer();
		boolean online = server != null && server.getPlayerList().getPlayer(e.target()) != null;
		List<String> lore = new ArrayList<>();
		lore.add("§7Bounty: " + Msg.moneyStr(e.total()));
		lore.add("§7Contributors: §f" + e.contributors());
		if (e.topContributor() != null) {
			lore.add("§7Top contributor: §f" + EconomyService.nameOrUnknown(e.topContributor()) + " §8(" + Msg.moneyStr(e.topAmount()) + "§8)");
		}
		lore.add("§7Status: " + (online ? "§aOnline" : "§8Offline"));
		lore.add("");
		lore.add(self ? "§8That's you." : "§eClick to raise the bounty");
		GuiElementBuilder b = Gui.item(Items.PLAYER_HEAD, "§c#" + row.rank() + " §f" + name, lore);
		head(b, server, e.target(), name);
		return b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(viewer, index, type)) return;
			Gui.click(viewer);
			if (self) {
				Msg.error(viewer, "You can't place a bounty on yourself.");
				Gui.failSound(viewer);
				return;
			}
			askAmount(viewer, e.target());
		});
	}

	/** Online: skin data from the profile. Otherwise by name, and the client loads the skin. */
	private static void head(GuiElementBuilder b, MinecraftServer server, UUID id, String name) {
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(id);
		if (online != null && !online.getGameProfile().properties().isEmpty() && PLAYER_NAME.matcher(online.getGameProfile().name()).matches()) {
			b.setProfile(ResolvableProfile.createResolved(online.getGameProfile()));
		} else if (PLAYER_NAME.matcher(name).matches()) {
			b.setProfile(ResolvableProfile.createUnresolved(name));
		}
	}

	private static GuiElementBuilder info() {
		BountyConfig cfg = Bounties.config();
		List<String> lore = new ArrayList<>();
		lore.add("§7Put a bounty on a player:");
		lore.add("§e/bounty <player> <amount>");
		lore.add("");
		lore.add("§7Whoever kills them in §cPvP §7gets it all,");
		lore.add("§7minus a §f" + percent(cfg.feePercent) + " §7fee §8(Treasury)§7.");
		lore.add("§7Bounties on the same player");
		lore.add("§7stack up.");
		lore.add("§7Dying to the environment or");
		lore.add("§7by suicide keeps the bounty.");
		if (cfg.blockSameTeam) lore.add("§7No payout for kills in the same team/clan.");
		if (cfg.blockSameIp) lore.add("§7No payout between players on the same IP.");
		if (cfg.minKillerPlaytimeMinutes > 0) lore.add("§7The killer needs at least §f" + cfg.minKillerPlaytimeMinutes + " min §7of playtime.");
		lore.add("");
		lore.add("§7Minimum: " + Msg.moneyStr(cfg.minAmount));
		return Gui.item(Items.BOOK, "§6How it works", lore);
	}

	private static GuiElementBuilder ownHead(ServerPlayer player) {
		long total = Bounties.LIVE.total(player.getUUID());
		int count = Bounties.LIVE.contributions(player.getUUID()).size();
		GuiElementBuilder b = total > 0
			? Gui.item(Items.PLAYER_HEAD, "§cBounty on you", "§7Amount: " + Msg.moneyStr(total), "§7Contributors: §f" + count, "", "§8Watch your back!")
			: Gui.item(Items.PLAYER_HEAD, "§aNo bounty on you", "§7Nobody has put a bounty", "§7on you.");
		head(b, player.level().getServer(), player.getUUID(), player.getGameProfile().name());
		return b;
	}

	private static GuiElementBuilder history() {
		List<BountyData.Claim> list = Bounties.LIVE.history();
		List<String> lore = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (int i = 0; i < Math.min(LORE_LIST_MAX, list.size()); i++) {
			BountyData.Claim c = list.get(i);
			lore.add("§f" + c.killerName + " §7→ §c" + c.victimName + " " + Msg.moneyStr(c.total) + " §8(" + TimeUtil.formatDuration(now - c.at) + " ago)");
		}
		if (lore.isEmpty()) lore.add("§8No bounties claimed yet.");
		return Gui.item(Items.IRON_SWORD, "§6Recent bounty hunts", lore);
	}

	private static GuiElementBuilder mine(UUID self) {
		Map<UUID, Long> mine = Bounties.LIVE.contributedBy(self);
		List<String> lore = new ArrayList<>();
		long sum = 0;
		int shown = 0;
		for (Map.Entry<UUID, Long> e : mine.entrySet()) {
			sum += e.getValue();
			if (shown++ < LORE_LIST_MAX) lore.add("§c" + EconomyService.nameOrUnknown(e.getKey()) + "§7: " + Msg.moneyStr(e.getValue()));
		}
		if (mine.size() > LORE_LIST_MAX) lore.add("§8… and " + (mine.size() - LORE_LIST_MAX) + " more");
		if (lore.isEmpty()) lore.add("§8You haven't placed any bounties.");
		else {
			lore.add("");
			lore.add("§7Total: " + Msg.moneyStr(sum));
		}
		return Gui.item(Items.GOLD_INGOT, "§6Your bounties", lore);
	}

	static void askName(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		Gui.textInput(player, "§8Enter a player name", "", input -> {
			Optional<UUID> target = BountyCommands.resolvePlayer(player.level().getServer(), input);
			if (target.isEmpty()) {
				Msg.error(player, "Player §f" + input + " §cnot found.");
				Gui.failSound(player);
				open(player);
				return;
			}
			if (target.get().equals(player.getUUID())) {
				Msg.error(player, "You can't place a bounty on yourself.");
				Gui.failSound(player);
				open(player);
				return;
			}
			askAmount(player, target.get());
		}, () -> open(player));
	}

	static void askAmount(ServerPlayer player, UUID target) {
		if (!Gui.canOpen(player)) return;
		String name = EconomyService.nameOrUnknown(target);
		Gui.textInput(player, "§8Amount for §c" + name, Long.toString(Bounties.config().minAmount / 100), input -> {
			OptionalLong amount = Money.parse(input);
			if (amount.isEmpty()) {
				Msg.error(player, "Invalid amount. Examples: §e100§c, §e2.5k§c, §e1m");
				Gui.failSound(player);
				open(player);
				return;
			}
			confirm(player, target, amount.getAsLong(), () -> open(player));
		}, () -> open(player));
	}

	/**
	 * Dry run without charging (errors go to chat), then the dialog. Only confirming charges, after checking
	 * everything again. {@code back} (null from the command) reopens the list afterwards or on cancel;
	 * without it the dialog just closes.
	 *
	 * @return whether the dialog opened
	 */
	static boolean confirm(ServerPlayer player, UUID target, long amount, Runnable back) {
		if (!BountyCommands.precheck(player, target, amount)) {
			if (back != null) back.run();
			return false;
		}
		if (!Gui.canOpen(player)) return false;
		String name = EconomyService.nameOrUnknown(target);
		long after = Bounties.LIVE.total(target) + amount;
		long balance = EconomyService.balance(player.getUUID());
		GuiElementBuilder preview = Gui.item(Items.PLAYER_HEAD, "§cBounty on " + name,
			"§7Your stake: " + Msg.moneyStr(amount),
			"§7Bounty after: " + Msg.moneyStr(after),
			"§7Your balance: " + Msg.moneyStr(balance) + " §8→ " + Money.format(balance - amount),
			"",
			"§8The money is gone until someone",
			"§8kills §c" + name + " §8in PvP – no refunds.");
		head(preview, player.level().getServer(), target, name);
		Gui.confirm(player, TITLE_CONFIRM, preview, () -> {
			BountyCommands.placeAndReport(player, target, amount);
			if (back != null && Gui.canOpen(player)) back.run();
		}, back != null ? back : () -> Msg.info(player, "Cancelled – no bounty placed, nothing charged."));
		return true;
	}

	static String percent(double pct) {
		return pct == Math.rint(pct) ? (long) pct + "%" : String.format(java.util.Locale.ROOT, "%.1f%%", pct);
	}

	static String contributors(int n) {
		return n + (n == 1 ? " contributor" : " contributors");
	}
}
