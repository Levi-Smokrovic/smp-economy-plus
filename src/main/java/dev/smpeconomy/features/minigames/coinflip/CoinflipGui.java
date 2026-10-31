package dev.smpeconomy.features.minigames.coinflip;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.minigames.MinigamesFeature;

/**
 * /coinflip: list of open flips, confirm dialogs, animation after accepting (the result is already decided and
 * booked), messages and double-or-nothing. Commands and GUI call the same actions.
 */
public final class CoinflipGui {
	public static final String TITLE = "§6§lCoinflip";
	public static final String ANIM_TITLE = "§6§lCoinflip §8» §7Flipping…";
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int[] FRAMES = {2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 9, 11};

	private CoinflipGui() {}

	public static boolean create(ServerPlayer player, long amount) {
		Coinflips.Result r = MinigamesFeature.coinflips().create(player.getUUID(), player.getGameProfile().name(), amount);
		if (!r.ok()) {
			Msg.error(player, r.error());
			Gui.failSound(player);
			return false;
		}
		Gui.successSound(player);
		Msg.send(player, Msg.prefixed("§aCoinflip for " + Msg.moneyStr(amount) + " §acreated. §7Waiting for an opponent… ")
			.append(Msg.clickRun("§c[Cancel]", "/coinflip cancel", "§7Cancel all your open coinflips")));
		// only from announceMinBet and once per cooldown per player, otherwise /cf + /cf cancel spams chat
		if (MinigamesFeature.coinflips().claimCreateAnnouncement(player.getUUID(), amount)) {
			Component msg = Msg.prefixed("§e" + player.getGameProfile().name() + " §7started a coinflip for " + Msg.moneyStr(amount) + "§7. ")
				.append(Msg.clickRun("§a[Accept]", "/coinflip", "§7Open the coinflip list"));
			announce(player.level().getServer(), msg, player.getUUID(), null);
		}
		return true;
	}

	public static void cancelAll(ServerPlayer player) {
		int count = MinigamesFeature.coinflips().openOf(player.getUUID()).size();
		if (count == 0) {
			Msg.error(player, "You don't have any open coinflips.");
			return;
		}
		long refunded = MinigamesFeature.coinflips().cancelAll(player.getUUID());
		if (refunded > 0) {
			Msg.success(player, "Coinflip cancelled – " + Msg.moneyStr(refunded) + " §arefunded.");
			Gui.successSound(player);
		} else {
			Msg.error(player, "Cancelling failed.");
		}
	}

	/**
	 * Everything is re-validated. On success: tell the creator (even offline), animate for the acceptor, then
	 * result, announcement and possibly double-or-nothing.
	 */
	public static void accept(ServerPlayer player, String flipId, long expectedAmount, int returnPage) {
		Coinflips.Outcome o = MinigamesFeature.coinflips().accept(player.getUUID(), player.getGameProfile().name(), flipId, expectedAmount);
		if (!o.ok()) {
			Msg.error(player, o.error());
			Gui.failSound(player);
			if (Gui.canOpen(player)) open(player, returnPage);
			return;
		}
		Coinflips.Flip f = o.flip();
		String vs = "§7against §e" + o.acceptorName();
		if (o.creatorWon()) {
			Msg.sendOrQueue(f.creator, "§aYou won your coinflip for " + Msg.moneyStr(f.amount) + " §a" + vs + "§a: §a+" + Money.format(o.payout())
				+ " §8(fee " + Money.format(o.fee()) + ")");
		} else {
			Msg.sendOrQueue(f.creator, "§cYou lost your coinflip for " + Msg.moneyStr(f.amount) + " §c" + vs + "§c.");
		}
		if (o.creatorWon()) offerDoubleOrNothing(player.level().getServer(), o, 40);
		if (Bots.isBot(player) || !Gui.canOpen(player)) {
			finish(player, o);
			return;
		}
		animate(player, o);
	}

	private static void finish(ServerPlayer player, Coinflips.Outcome o) {
		Coinflips.Flip f = o.flip();
		if (!o.creatorWon()) {
			Msg.send(player, Msg.prefixed("§a§lYou won! §7Coinflip against §e" + f.creatorName + "§7: §a+" + Money.format(o.payout())
				+ " §8(pot " + Money.format(o.pot()) + ", fee " + Money.format(o.fee()) + ")"));
		} else {
			Msg.send(player, Msg.prefixed("§c§lYou lost! §e" + f.creatorName + " §7wins the coinflip for " + Msg.moneyStr(f.amount) + "§7."));
		}
		MinecraftServer server = player.level().getServer();
		if (MinigamesFeature.coinflips().announcesResult(f.amount)) {
			announce(server, Msg.prefixed("§e" + o.winnerName() + " §7won a coinflip for " + Msg.moneyStr(f.amount) + " §7against §e" + o.loserName()
				+ " §8(+" + Money.format(o.payout()) + ")§7!"), f.creator, o.acceptor());
		}
		if (!o.creatorWon()) offerDoubleOrNothing(server, o, 10);
	}

	private static void offerDoubleOrNothing(MinecraftServer server, Coinflips.Outcome o, int delayTicks) {
		if (server == null || !MinigamesFeature.config().coinflip.offerDoubleOrNothing || o.payout() <= 0) return;
		UUID winner = o.winner();
		long won = o.payout();
		Scheduler.runLater(delayTicks, () -> {
			if (Services.find(Services.DoubleOrNothing.class).isEmpty()) return;
			ServerPlayer p = server.getPlayerList().getPlayer(winner);
			if (p == null || Bots.isBot(p) || !Gui.canOpen(p)) return;
			try {
				Services.find(Services.DoubleOrNothing.class).get().offer(p, won);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Double-or-nothing offer failed", e);
			}
		});
	}

	static void announce(MinecraftServer server, Component msg, UUID except1, UUID except2) {
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(p) || p.getUUID().equals(except1) || p.getUUID().equals(except2)) continue;
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) p.sendSystemMessage(msg);
		}
	}

	public static void open(ServerPlayer player, int page) {
		SimpleGui gui = Gui.chest(player, 6, TITLE);
		List<Gui.Pager<Coinflips.Flip>> holder = new ArrayList<>(1);
		Gui.Pager<Coinflips.Flip> pager = new Gui.Pager<>(gui, () -> MinigamesFeature.coinflips().open(),
			f -> entry(player, f, () -> holder.get(0).page()));
		holder.add(pager);
		pager.withMenuButton(); // last row is full: main menu goes on the page indicator (49)
		Coinflips flips = MinigamesFeature.coinflips();
		gui.setSlot(45, Gui.button(Items.GOLD_INGOT, "§a§lNew Coinflip", () -> newFlipInput(player, pager.page()),
			"§7Set an amount – whoever accepts", "§7bets the same. 50/50, the winner", "§7gets the pot §8(minus a "
				+ fmtPercent(MinigamesFeature.config().coinflip.feePercent) + " fee)§7.", "",
			"§8Min " + Money.format(MinigamesFeature.config().coinflip.minBet) + " • Max " + Money.formatShort(MinigamesFeature.config().coinflip.maxBet),
			"§eClick: enter amount"));
		int own = flips.openOf(player.getUUID()).size();
		if (own > 0) {
			gui.setSlot(46, Gui.button(Items.RED_DYE, "§cCancel mine §8(" + own + ")", () -> {
				cancelAll(player);
				if (Gui.canOpen(player)) open(player, pager.page());
			}, "§7Cancel and refund all", "§7your open coinflips."));
		}
		if (houseAvailable(player.level().getServer())) {
			gui.setSlot(47, Gui.button(Items.EMERALD, "§eAgainst the House", () -> {
				// opens casino's house coinflip through its command, avoiding a package dependency
				MinecraftServer server = player.level().getServer();
				if (server != null && Gui.canOpen(player)) CommandRegistry.run(player, "coinflip " + houseLiteral(server));
			}, "§7Play against the Treasury", "§7(Casino, 48% for ×2).", "", "§eClick: open §8(/coinflip house <amount>)"));
		}
		gui.setSlot(51, Gui.button(Items.CLOCK, "§7Refresh", pager::refresh));
		gui.setSlot(52, statsItem(player.getUUID()));
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(page);
		gui.open();
	}

	private static GuiElementBuilder entry(ServerPlayer viewer, Coinflips.Flip f, java.util.function.IntSupplier page) {
		boolean own = f.creator.equals(viewer.getUUID());
		long pot = f.amount * 2;
		long fee = MinigamesFeature.coinflips().feeFor(pot);
		long age = System.currentTimeMillis() - f.createdAt;
		List<String> lore = new ArrayList<>();
		lore.add("§7Bet: " + Msg.moneyStr(f.amount));
		lore.add("§7Win: §a" + Money.format(pot - fee) + " §8(fee " + Money.format(fee) + ")");
		lore.add("§7Open for: §f" + TimeUtil.formatDuration(age));
		lore.add("");
		lore.add(own ? "§cClick: Cancel" : "§aClick: Accept");
		GuiElementBuilder b = Gui.button(Items.PLAYER_HEAD, "§e" + displayName(f), () -> {
			if (own) confirmCancel(viewer, f, page.getAsInt());
			else confirmAccept(viewer, f, page.getAsInt());
		}, lore.toArray(String[]::new));
		if (PLAYER_NAME.matcher(f.creatorName).matches()) b.setProfile(f.creatorName);
		return b;
	}

	private static void confirmAccept(ServerPlayer player, Coinflips.Flip f, int page) {
		long pot = f.amount * 2;
		long fee = MinigamesFeature.coinflips().feeFor(pot);
		GuiElementBuilder preview = Gui.item(Items.PLAYER_HEAD, "§eCoinflip against " + displayName(f),
			"§7Your bet: " + Msg.moneyStr(f.amount),
			"§7Win if you win: §a" + Money.format(pot - fee),
			"§7Your balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID())),
			"", "§850/50 – the result is decided instantly.");
		if (PLAYER_NAME.matcher(f.creatorName).matches()) preview.setProfile(f.creatorName);
		String id = f.id;
		long amount = f.amount;
		Gui.confirm(player, "§8Accept coinflip?", preview, () -> accept(player, id, amount, page), () -> open(player, page));
	}

	private static void confirmCancel(ServerPlayer player, Coinflips.Flip f, int page) {
		String id = f.id;
		Gui.confirm(player, "§8Cancel coinflip?", Gui.item(Items.BARRIER, "§cCancel",
			"§7Get your bet of " + Msg.moneyStr(f.amount) + " §7back."), () -> {
				Coinflips.Result r = MinigamesFeature.coinflips().cancel(player.getUUID(), id);
				if (r.ok()) {
					Msg.success(player, "Coinflip cancelled – " + Msg.moneyStr(r.flip().amount) + " §arefunded.");
					Gui.successSound(player);
				} else {
					Msg.error(player, r.error());
					Gui.failSound(player);
				}
				if (Gui.canOpen(player)) open(player, page);
			}, () -> open(player, page));
	}

	private static void newFlipInput(ServerPlayer player, int page) {
		Gui.textInput(player, "§8Enter your bet", Long.toString(Math.max(1, MinigamesFeature.config().coinflip.minBet / 100)), text -> {
			OptionalLong amount = Money.parse(text);
			if (amount.isEmpty()) {
				Msg.error(player, "Invalid amount. Examples: §e100§c, §e2.5k§c, §e1m");
				Gui.failSound(player);
			} else {
				create(player, amount.getAsLong());
			}
			if (Gui.canOpen(player)) open(player, page);
		}, () -> open(player, page));
	}

	private static GuiElementBuilder statsItem(UUID player) {
		long wins = PlayerDataService.getCounter(player, Coinflips.C_WINS);
		long losses = PlayerDataService.getCounter(player, Coinflips.C_LOSSES);
		long won = PlayerDataService.getCounter(player, Coinflips.C_WON_CENTS);
		long lost = PlayerDataService.getCounter(player, Coinflips.C_LOST_CENTS);
		long net = won - lost;
		return Gui.item(Items.BOOK, "§eYour Record",
			"§7Wins: §a" + wins + " §8• §7Losses: §c" + losses,
			"§7Won: §a+" + Money.format(won),
			"§7Lost: §c-" + Money.format(lost),
			"§7Net: " + (net >= 0 ? "§a+" : "§c") + Money.format(net));
	}

	/** Looks in the command tree, avoiding a dependency on casino. */
	private static boolean houseAvailable(MinecraftServer server) {
		return houseLiteral(server) != null;
	}

	private static String houseLiteral(MinecraftServer server) {
		if (server == null) return null;
		var root = CommandRegistry.root().getChild("coinflip");
		if (root == null) return null;
		return root.getChild("house") != null ? "house" : null;
	}

	private static String displayName(Coinflips.Flip f) {
		return f.creatorName.isEmpty() ? EconomyService.nameOrUnknown(f.creator) : f.creatorName;
	}

	private static String fmtPercent(double p) {
		return (Math.abs(p - Math.rint(p)) < 1e-9 ? Long.toString(Math.round(p)) : String.format(java.util.Locale.ROOT, "%.1f", p)) + "%";
	}

	private static void animate(ServerPlayer player, Coinflips.Outcome o) {
		boolean[] finished = {false};
		Runnable finishOnce = () -> {
			if (finished[0]) return;
			finished[0] = true;
			finish(player, o);
		};
		SimpleGui gui = new SmpGui(Gui.menuType(3), player, false) {
			@Override
			protected void onClosed() {
				finishOnce.run(); // ESC, disconnect or stop: still report the result, the money is already booked
			}
		};
		gui.setTitle(Msg.legacy(ANIM_TITLE));
		for (int i = 0; i < 27; i++) gui.setSlot(i, Gui.filler());
		Coinflips.Flip f = o.flip();
		String a = displayName(f);
		String b = o.acceptorName();
		gui.setSlot(11, head(a, "§e" + a, "§7Creator"));
		gui.setSlot(15, head(b, "§e" + b, "§7Challenger"));
		gui.setSlot(22, Gui.closeButton(gui));
		int winnerIdx = o.creatorWon() ? 0 : 1;
		int frames = FRAMES.length;
		int offset = ((winnerIdx - (frames - 1)) % 2 + 2) % 2;
		gui.setSlot(13, head(a, "§7?", "§8The coin is flying…"));
		gui.open();
		step(player, gui, o, a, b, 0, offset, finishOnce);
	}

	private static void step(ServerPlayer player, SimpleGui gui, Coinflips.Outcome o, String a, String b, int frame, int offset, Runnable finishOnce) {
		if (!gui.isOpen()) return;
		if (frame >= FRAMES.length) {
			boolean viewerWon = !o.creatorWon();
			String winner = o.creatorWon() ? a : b;
			gui.setSlot(13, head(winner, "§a§l" + winner + " wins!", "§7Pot: " + Msg.moneyStr(o.pot()), "§7Fee: §c" + Money.format(o.fee()),
				"§7Payout: " + Msg.moneyStr(o.payout())).glow());
			if (viewerWon) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.0f);
			} else {
				Gui.failSound(player);
			}
			finishOnce.run();
			return;
		}
		boolean showA = (frame + offset) % 2 == 0;
		String who = showA ? a : b;
		gui.setSlot(13, head(who, "§f" + who, "§8The coin is flying…"));
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.8f + frame * 0.06f);
		Scheduler.runLater(FRAMES[frame], () -> step(player, gui, o, a, b, frame + 1, offset, finishOnce));
	}

	private static GuiElementBuilder head(String profileName, String name, String... lore) {
		GuiElementBuilder b = Gui.item(Items.PLAYER_HEAD, name, lore);
		if (profileName != null && PLAYER_NAME.matcher(profileName).matches()) b.setProfile(profileName);
		return b;
	}
}
