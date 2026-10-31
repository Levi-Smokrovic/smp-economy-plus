package dev.smpeconomy.features.events;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;

/**
 * {@code /event} (overview; admin: start, stop, status, next, setrank), {@code /rank [player]} and {@code
 * /lottery} (GUI, buy, tickets, history, info; admin: draw [confirm] [numbers], status). Word arguments here deliberately get no SuggestionProvider: the client would then ask the
 * server, and Brigadier's server completion lists all sibling literals without a permission check (a player
 * would see draw and status on {@code /lotto }).
 */
final class EventsCommands {
	static final String NODE_EVENT = "smpeconomy.events";
	static final String NODE_RANK = "smpeconomy.rank";
	static final String NODE_LOTTO = "smpeconomy.lottery";
	static final String NODE_ADMIN = "smpeconomy.admin.events";
	private static final UUID NOBODY = new UUID(0, 0);
	/** Random parameter for /event start. */
	private static final String RANDOM = "random";

	private EventsCommands() {}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection env) {
		dispatcher.register(Commands.literal("event")
			.requires(Perms.require(NODE_EVENT))
			.executes(EventsCommands::hub)
			.then(Commands.literal("info").executes(EventsCommands::info))
			.then(Commands.literal("start").requires(Perms.requireAdmin(NODE_ADMIN))
				.then(withParam(Commands.literal("marketday"), EventType.MARKET_DAY, "category"))
				.then(withParam(Commands.literal("goldrush"), EventType.GOLD_RUSH, "job"))
				.then(luckyHour(Commands.literal("luckyhour"))))
			.then(Commands.literal("stop").requires(Perms.requireAdmin(NODE_ADMIN)).executes(EventsCommands::stop))
			.then(Commands.literal("status").requires(Perms.requireAdmin(NODE_ADMIN))
				.executes(ctx -> status(ctx, null))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
					.executes(ctx -> status(ctx, Args.knownPlayer(ctx, "player")))))
			.then(Commands.literal("next").requires(Perms.requireAdmin(NODE_ADMIN))
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 7 * 24 * 60)).executes(EventsCommands::next)))
			.then(setRank("setrank")));

		dispatcher.register(rankRoot("rank"));

		dispatcher.register(lotteryRoot("lottery"));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> setRank(String literal) {
		return Commands.literal(literal).requires(Perms.requireAdmin(NODE_ADMIN))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.then(Commands.argument("sales", StringArgumentType.greedyString()).executes(EventsCommands::setRank)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> rankRoot(String name) {
		return Commands.literal(name)
			.requires(Perms.require(NODE_RANK))
			.executes(ctx -> rank(ctx, null))
			.then(Commands.literal("list").executes(EventsCommands::rankList))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(ctx -> rank(ctx, Args.knownPlayer(ctx, "player"))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> lotteryRoot(String name) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
			.requires(Perms.require(NODE_LOTTO))
			.executes(EventsCommands::lottoGui)
			.then(lottoBuyNode("buy"))
			.then(Commands.literal("tickets").executes(EventsCommands::lottoTickets))
			.then(Commands.literal("history").executes(EventsCommands::lottoHistory))
			.then(Commands.literal("info").executes(EventsCommands::lottoInfo))
			// Admin: draw now (before the slot with sold tickets only with "confirm"); with numbers (jackpot
			// first) as a test hook with a known result, only with lottoForcedDraws, publicly marked, in the
			// admin log
			.then(Commands.literal("draw").requires(Perms.requireAdmin(NODE_ADMIN))
				.executes(ctx -> lottoDraw(ctx, null))
				.then(Commands.argument("numbers", StringArgumentType.greedyString())
					.executes(ctx -> lottoDraw(ctx, StringArgumentType.getString(ctx, "numbers")))))
			.then(Commands.literal("status").requires(Perms.requireAdmin(NODE_ADMIN))
				.executes(ctx -> lottoStatus(ctx, null))
				.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
					.executes(ctx -> lottoStatus(ctx, Args.knownPlayer(ctx, "player")))));
		return root;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> lottoBuyNode(String literal) {
		return Commands.literal(literal)
			.then(Commands.argument("amount", IntegerArgumentType.integer(1, EventsConfig.MAX_LOTTO_TICKETS)).executes(EventsCommands::lottoBuy));
	}

	private static int lottoUsage(CommandContext<CommandSourceStack> ctx) {
		Msg.error(ctx.getSource(), "Usage: §f/lottery [buy <amount>|tickets|history|info]");
		return 0;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> luckyHour(LiteralArgumentBuilder<CommandSourceStack> node) {
		return node
			.executes(ctx -> start(ctx, EventType.LUCKY_HOUR, null, -1))
			.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
				.executes(ctx -> start(ctx, EventType.LUCKY_HOUR, null, IntegerArgumentType.getInteger(ctx, "minutes"))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> withParam(LiteralArgumentBuilder<CommandSourceStack> node, EventType type, String argName) {
		return node
			.executes(ctx -> start(ctx, type, null, -1))
			.then(Commands.argument(argName, StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(paramSuggestions(type), b))
				.executes(ctx -> start(ctx, type, StringArgumentType.getString(ctx, argName), -1))
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
					.executes(ctx -> start(ctx, type, StringArgumentType.getString(ctx, argName), IntegerArgumentType.getInteger(ctx, "minutes")))));
	}

	private static List<String> paramSuggestions(EventType type) {
		List<String> list = new ArrayList<>();
		list.add(RANDOM);
		EventsConfig cfg = EventsFeature.CONFIG.get();
		// Only categories with eligible items right now (the config skips the others with a log warning)
		if (type == EventType.MARKET_DAY) cfg.marketDayCategories.forEach(c -> {
			if (!cfg.categoryItems(c.id).isEmpty()) list.add(c.id);
		});
		if (type == EventType.GOLD_RUSH) cfg.goldRushJobs.forEach(j -> list.add(j.id));
		return list;
	}

	private static int hub(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return info(ctx);
		EventsGui.openHub(player);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		long now = System.currentTimeMillis();
		Optional<EventManager.Active> a = Events.active();
		if (a.isPresent()) {
			Msg.info(src, a.get().type().color() + "§l" + a.get().type().label() + " §8» " + EventsRuntime.effectText(a.get()) + " §7– §e"
				+ TimeUtil.formatDuration(a.get().remaining(now)) + " §7left");
		} else {
			long next = EventsFeature.EVENTS.nextEventAt();
			Msg.info(src, "No event running right now." + (EventsFeature.CONFIG.get().randomEvents && next > now
				? " Next one in about §e" + TimeUtil.formatDuration((next - now + 59_999) / 60_000 * 60_000) + "§7." : ""));
		}
		ServerPlayer player = src.getPlayer();
		if (player != null) {
			TraderRank.Rank r = TraderRank.rank(player.getUUID());
			Msg.info(src, "Your Trader Rank: " + r.display() + " §7(§a" + r.bonusLabel() + "§7) §8– /rank");
		}
		return 1;
	}

	/** Event id or English name without spaces; null if unknown. */
	private static int start(CommandContext<CommandSourceStack> ctx, EventType type, String param, int minutes) {
		CommandSourceStack src = ctx.getSource();
		String p = param == null || param.equalsIgnoreCase(RANDOM) ? null : param;
		long duration = minutes > 0 ? minutes * 60_000L : EventsFeature.EVENTS.randomDurationMs();
		try {
			EventManager.Active a = EventsRuntime.adminStart(src.getServer(), type, p, duration, src.getTextName());
			String shown = a.param() == null ? null : type == EventType.GOLD_RUSH ? EventsRuntime.jobName(a.param()) : EventsRuntime.categoryName(a.param());
			Msg.success(src, type.label() + " started" + (shown != null ? " (" + shown + ")" : "") + " for "
				+ TimeUtil.formatDuration(duration) + ".");
			return 1;
		} catch (IllegalArgumentException e) {
			Msg.error(src, e.getMessage() + (type == EventType.MARKET_DAY || type == EventType.GOLD_RUSH
				? " §7(options: " + String.join(", ", paramSuggestions(type).subList(1, paramSuggestions(type).size())) + ")" : ""));
			return 0;
		}
	}

	private static int stop(CommandContext<CommandSourceStack> ctx) {
		Optional<EventManager.Active> ended = EventsRuntime.adminStop(ctx.getSource().getServer());
		if (ended.isEmpty()) {
			Msg.error(ctx.getSource(), "No event running right now.");
			return 0;
		}
		Msg.success(ctx.getSource(), ended.get().type().label() + " stopped.");
		return 1;
	}

	/**
	 * Machine-readable (RCON, E2E): {@code event active=... param=... remaining=<s> next=<s> jobMult=...
	 * chatMult=... luckyFlag=... bar=...}, with a player also {@code player=<name> rank=<id> sales=<cents>
	 * rankMult=...}.
	 */
	private static int status(CommandContext<CommandSourceStack> ctx, UUID target) {
		long now = System.currentTimeMillis();
		Optional<EventManager.Active> a = Events.active();
		String param = a.map(EventManager.Active::param).orElse(null);
		double jobMult = a.isPresent() && a.get().type() == EventType.GOLD_RUSH ? Hooks.jobMultiplier(NOBODY, param) : 1.0;
		long next = EventsFeature.EVENTS.nextEventAt();
		String line = "event active=" + a.map(x -> x.type().id()).orElse("none")
			+ " param=" + (param == null ? "-" : param)
			+ " remaining=" + a.map(x -> x.remaining(now) / 1000).orElse(0L)
			+ " next=" + (next > now ? (next - now) / 1000 : 0)
			+ " jobMult=" + fmt(jobMult)
			+ " chatMult=" + fmt(Hooks.chatGameMultiplier(NOBODY))
			+ " luckyFlag=" + Hooks.flag(Events.FLAG_LUCKY_HOUR)
			+ " bar=" + (EventsRuntime.barTitleForDebug() != null);
		if (target != null) {
			TraderRank.Rank r = TraderRank.rank(target);
			line += " player=" + EconomyService.nameOrUnknown(target) + " rank=" + r.id() + " sales=" + TraderRank.lifetimeSales(target)
				+ " rankMult=" + fmt(r.multiplier());
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return 1;
	}

	private static int next(CommandContext<CommandSourceStack> ctx) {
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		if (Events.active().isPresent()) {
			Msg.error(ctx.getSource(), "An event is running – use /event stop first.");
			return 0;
		}
		EventsFeature.EVENTS.scheduleNext(System.currentTimeMillis() + minutes * 60_000L);
		Msg.success(ctx.getSource(), "Next random event in " + minutes + " min.");
		return 1;
	}

	private static int setRank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		long cents = Args.moneyAllowZero(ctx, "sales");
		TraderRank.setSales(target, cents);
		TraderRank.Rank r = TraderRank.rank(target);
		Msg.success(ctx.getSource(), "Set " + EconomyService.nameOrUnknown(target) + "'s sales to " + Money.format(cents) + " – rank "
			+ r.display() + " §a(" + r.bonusLabel() + ") §8rank=" + r.id() + " sales=" + TraderRank.lifetimeSales(target));
		return 1;
	}

	private static int rank(CommandContext<CommandSourceStack> ctx, UUID target) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = target;
		if (id == null) id = src.getPlayerOrException().getUUID();
		boolean self = src.getPlayer() != null && src.getPlayer().getUUID().equals(id);
		long sales = TraderRank.lifetimeSales(id);
		TraderRank.Rank r = TraderRank.rank(id);
		Optional<TraderRank.Rank> next = TraderRank.next(r);
		String who = self ? "Your" : EconomyService.nameOrUnknown(id) + "'s";
		Msg.info(src, who + " Trader Rank: " + r.display() + " §7(§a" + r.bonusLabel() + " §7sell bonus)");
		Msg.info(src, "Rank sales: " + Msg.moneyStr(sales) + (next.isPresent()
			? " §8– " + next.get().display() + " §7at " + Msg.moneyStr(next.get().minSales()) + " §8(" + Money.format(next.get().minSales() - sales) + " to go)"
			: " §6– top rank!"));
		if (self && next.isPresent()) Msg.send(src, Msg.prefixed(Msg.legacy(EventsGui.progressBar(TraderRank.progress(sales, r, next)) + " ")
			.append(Msg.clickRun("§e[All ranks]", "/rank list", "§7Show all ranks"))));
		return 1;
	}

	private static int rankList(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player != null) {
			EventsGui.openRanks(player, null);
			return 1;
		}
		for (TraderRank.Rank r : TraderRank.ranks()) {
			Msg.info(ctx.getSource(), r.display() + " §7from " + Msg.moneyStr(r.minSales()) + " §8– §a" + r.bonusLabel());
		}
		return 1;
	}

	private static int lottoGui(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return lottoInfo(ctx);
		EventsGui.openLotto(player, null);
		return 1;
	}

	private static int lottoBuy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		return EventsGui.buy(player, IntegerArgumentType.getInteger(ctx, "amount")).ok() ? 1 : 0;
	}

	private static int lottoInfo(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		long now = System.currentTimeMillis();
		Msg.info(src, "§6Lottery §8» §6§lJackpot " + Money.format(lotto.jackpot()) + "§7 – draw #" + lotto.drawId() + " in §e"
			+ TimeUtil.formatDuration(Math.max(0, lotto.nextDrawAt() - now)) + " §8(" + cfg.lottoDrawTime + ")§7, " + lotto.soldCount() + "/"
			+ lotto.ticketCount() + " tickets sold.");
		if (cfg.lottoEnabled && lotto.isReady() && !lotto.covered()) Msg.info(src, "§cTicket sales are paused §7until the Treasury can cover the prizes.");
		ServerPlayer player = src.getPlayer();
		if (player != null) {
			Msg.info(src, "Your tickets: §f" + lotto.tickets(player.getUUID()) + "§7/§f" + cfg.lottoMaxTicketsPerPlayer + " §8– "
				+ Money.format(cfg.lottoTicketPrice) + " per ticket, §e/lottery buy <amount>§8, §e/lottery tickets");
		}
		return 1;
	}

	private static int lottoTickets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		UUID id = player.getUUID();
		List<Integer> numbers = lotto.numbers(id);
		if (numbers.isEmpty()) {
			Msg.info(player, "You have no tickets for draw #" + lotto.drawId() + " yet §8– §e/lottery buy <amount> §8(" + Money.format(cfg.lottoTicketPrice)
				+ " each, jackpot " + Money.format(lotto.jackpot()) + ")");
		} else {
			Msg.info(player, "Your tickets for draw #" + lotto.drawId() + " §8(§f" + numbers.size() + "§8/§f" + cfg.lottoMaxTicketsPerPlayer + "§8)§7:");
			for (String line : EventsGui.numberLines(numbers, 15)) Msg.send(player, Msg.legacy("  " + line));
			Msg.info(player, "Chance per prize: §f" + EventsGui.percent(lotto.chance(id)) + " §8(jackpot " + Money.format(lotto.jackpot())
				+ ")§7, any prize: §f" + EventsGui.percent(lotto.chanceAny(id)) + " §8– draw " + EventsRuntime.nextDrawText());
		}
		long owed = lotto.owedTo(id);
		if (owed > 0) Msg.info(player, "§eOutstanding winnings: " + Msg.moneyStr(owed) + " §8– paid out as soon as the Treasury can cover it.");
		return 1;
	}

	private static int lottoHistory(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		List<Lottery.Draw> draws = EventsFeature.LOTTO.draws();
		if (draws.isEmpty()) {
			Msg.info(src, "No lottery draws yet – the first one is " + EventsRuntime.nextDrawText() + ".");
			return 1;
		}
		Msg.info(src, "§6Lottery §8» §7last draws:");
		for (int i = 0; i < Math.min(5, draws.size()); i++) {
			Lottery.Draw d = draws.get(i);
			Msg.send(src, Msg.legacy("§f#" + d.id + " §8" + EventsGui.when(d.at) + " §8» " + (d.jackpotWon() ? "§a§lJACKPOT WON" : "§7jackpot rolled over")
				+ " §8(" + d.sold + "/" + d.ticketCount + " sold)" + EventsGui.adminMark(d)));
			for (String line : EventsGui.prizeLines(d)) Msg.send(src, Msg.legacy("  " + line));
		}
		return 1;
	}

	/**
	 * Draws right now. {@code args}: optional {@code confirm} and/or given winning numbers (jackpot first),
	 * otherwise random from the seed. Rejected: given numbers without {@code lottoForcedDraws} (test servers
	 * only, or an admin holding a ticket could pick the result), a draw that would be cancelled (no tickets:
	 * the jackpot must not grow by command), and an early draw with sold tickets without {@code confirm} (it
	 * replaces the scheduled draw).
	 */
	private static int lottoDraw(CommandContext<CommandSourceStack> ctx, String args) {
		CommandSourceStack src = ctx.getSource();
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		if (!lotto.isReady()) {
			Msg.error(src, "The lottery isn't ready yet.");
			return 0;
		}
		boolean confirm = false;
		List<String> numberTokens = new ArrayList<>();
		if (args != null) {
			for (String token : args.trim().split("[\\s,]+")) {
				if (token.isEmpty()) continue;
				if (token.equalsIgnoreCase("confirm")) confirm = true;
				else numberTokens.add(token);
			}
		}
		int[] forced = null;
		if (!numberTokens.isEmpty()) {
			try {
				forced = numberTokens.stream().mapToInt(Integer::parseInt).toArray();
			} catch (NumberFormatException e) {
				Msg.error(src, "Usage: §f/lottery draw [confirm] [numbers…] §7(" + lotto.winningCount() + " different numbers 1–" + lotto.ticketCount()
					+ ", jackpot first – test servers only)");
				return 0;
			}
			if (!cfg.lottoForcedDraws) {
				Msg.error(src, "Fixed winning numbers are for test servers only (events.json §flottoForcedDraws§c is false). §7Use §f/lottery draw§7 for a normal draw.");
				return 0;
			}
			String problem = lotto.checkNumbers(forced);
			if (problem != null) {
				Msg.error(src, problem);
				return 0;
			}
		}
		long now = System.currentTimeMillis();
		if (lotto.shouldSkip()) {
			Msg.error(src, "No tickets sold for draw #" + lotto.drawId() + " – nothing to draw. §7(The jackpot only rolls over after a draw with tickets"
				+ (!cfg.lottoEnabled ? "; the lottery is disabled" : !lotto.covered() ? "; ticket sales are paused until the Treasury covers the prizes" : "") + ".)");
			return 0;
		}
		if (forced == null && !confirm && lotto.isEarly(now) && lotto.soldCount() > 0) {
			int sold = lotto.soldCount();
			Msg.error(src, "Draw #" + lotto.drawId() + " is scheduled " + EventsRuntime.nextDrawText() + " and " + sold + (sold == 1 ? " ticket is" : " tickets are")
				+ " sold. Drawing now replaces that draw – use §f/lottery draw confirm§c.");
			return 0;
		}
		Lottery.Draw d = EventsRuntime.adminDraw(src.getServer(), forced);
		StringBuilder winners = new StringBuilder();
		for (Lottery.Prize p : d.prizes) {
			if (!winners.isEmpty()) winners.append(',');
			winners.append(p.rank).append(':').append(p.winner == null ? "-" : p.winnerName).append(':').append(p.paid).append(':').append(p.owed);
		}
		Msg.success(src, "Draw #" + d.id + ": " + (d.jackpotWon()
			? d.prizes.get(0).winnerName + " wins the " + Money.format(d.jackpot) + " jackpot."
			: "jackpot " + Money.format(d.jackpot) + " not won – rolls over to " + Money.format(d.nextJackpot) + ".")
			+ " §8draw=" + d.id + " numbers=" + joinInts(d.numbers()) + " jackpotWon=" + d.jackpotWon() + " jackpot=" + d.jackpot
			+ " nextJackpot=" + d.nextJackpot + " sold=" + d.sold + " forced=" + d.forced + " winners=" + winners
			+ " nextDraw=" + Math.max(0, lotto.nextDrawAt() - System.currentTimeMillis()) / 1000);
		return 1;
	}

	/**
	 * Machine-readable: {@code lotto draw=<id> jackpot=<cents> base=<cents> size=<n> sold=<n> players=<n>
	 * next=<s> owed=<cents> pot=<cents> prizes=<cents,...> ready=<bool> covered=<bool> need=<cents>
	 * reserve=<cents> max=<cents> forcedDraws=<bool> rollover=<bool> [last=<id>:<numbers>:<winners|...>:<next
	 * jackpot>:<forced>] [player=<name> playerTickets=<n> numbers=<n,...|-> balance=<cents> owedTo=<cents>]}.
	 * {@code pot} is what remains of the legacy pot, {@code need} the treasury balance ticket sales require,
	 * {@code covered} means sales are open.
	 */
	private static int lottoStatus(CommandContext<CommandSourceStack> ctx, UUID target) {
		Lottery lotto = EventsFeature.LOTTO;
		long now = System.currentTimeMillis();
		StringBuilder line = new StringBuilder("lotto draw=").append(lotto.drawId())
			.append(" jackpot=").append(lotto.jackpot())
			.append(" base=").append(EventsFeature.CONFIG.get().lottoJackpotBase)
			.append(" size=").append(lotto.ticketCount())
			.append(" sold=").append(lotto.soldCount())
			.append(" players=").append(lotto.participants())
			.append(" next=").append(Math.max(0, lotto.nextDrawAt() - now) / 1000)
			.append(" owed=").append(lotto.owedTotal())
			.append(" pot=").append(lotto.legacyPotBalance())
			.append(" prizes=").append(lotto.prizes().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
			.append(" ready=").append(lotto.isReady())
			.append(" covered=").append(lotto.covered())
			.append(" need=").append(lotto.coverageNeeded())
			.append(" reserve=").append(EventsFeature.CONFIG.get().lottoTreasuryReserve)
			.append(" max=").append(EventsFeature.CONFIG.get().lottoJackpotMax)
			.append(" forcedDraws=").append(EventsFeature.CONFIG.get().lottoForcedDraws)
			.append(" rollover=").append(EventsFeature.CONFIG.get().lottoRolloverWithoutTickets);
		List<Lottery.Draw> draws = lotto.draws();
		if (!draws.isEmpty()) {
			Lottery.Draw d = draws.get(0);
			line.append(" last=").append(d.id).append(':').append(joinInts(d.numbers())).append(':')
				.append(d.prizes.stream().map(p -> p.winner == null ? "-" : p.winnerName).collect(java.util.stream.Collectors.joining("|")))
				.append(':').append(d.nextJackpot).append(':').append(d.forced);
		}
		if (target != null) {
			List<Integer> numbers = lotto.numbers(target);
			line.append(" player=").append(EconomyService.nameOrUnknown(target))
				.append(" playerTickets=").append(numbers.size())
				.append(" numbers=").append(numbers.isEmpty() ? "-" : joinInts(numbers))
				.append(" balance=").append(EconomyService.balance(target))
				.append(" owedTo=").append(lotto.owedTo(target));
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return 1;
	}

	private static String joinInts(List<Integer> list) {
		return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
