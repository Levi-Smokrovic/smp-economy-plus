package dev.smpeconomy.features.minigames;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.minigames.chat.ChatGame;
import dev.smpeconomy.features.minigames.coinflip.CoinflipGui;
import dev.smpeconomy.features.minigames.coinflip.Coinflips;
import dev.smpeconomy.features.minigames.mega.MegaChallenge;
import dev.smpeconomy.features.minigames.mega.MegaConfig;
import dev.smpeconomy.features.minigames.mega.MegaGenerator;
import dev.smpeconomy.features.minigames.treasure.TreasureHunt;

final class MinigamesCommands {
	private static final String NODE_COINFLIP = "smpeconomy.coinflip";
	private static final String NODE_CHATGAME = "smpeconomy.chatgame";
	private static final String NODE_TREASURE = "smpeconomy.treasure";

	private MinigamesCommands() {}

	static void register(CommandDispatcher<CommandSourceStack> d) {
		LiteralCommandNode<CommandSourceStack> cf = d.register(Commands.literal("coinflip")
			.requires(Perms.require(NODE_COINFLIP))
			.executes(MinigamesCommands::coinflipGui)
			.then(Commands.literal("cancel").executes(MinigamesCommands::coinflipCancel))
			.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(MinigamesCommands::coinflipCreate)));
		d.register(Commands.literal("cf").requires(Perms.require(NODE_COINFLIP)).executes(MinigamesCommands::coinflipGui).redirect(cf));

		d.register(Commands.literal("chatgame")
			.requires(Perms.require(NODE_CHATGAME))
			.executes(MinigamesCommands::chatStatus)
			.then(Commands.literal("on").executes(ctx -> chatToggle(ctx, true)))
			.then(Commands.literal("off").executes(ctx -> chatToggle(ctx, false)))
			.then(chatStart())
			.then(Commands.literal("stop").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::chatStop))
			.then(Commands.literal("info").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::chatInfo))
			.then(megaTree()));
		d.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
			.then(Commands.literal("chatgame").then(chatStart())));

		d.register(treasureTree("treasure"));

		d.register(Commands.literal("minigames").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE))
			.executes(MinigamesCommands::status)
			.then(Commands.literal("status").executes(MinigamesCommands::status))
			.then(Commands.literal("balance").then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(MinigamesCommands::balance))));
	}

	private static int coinflipGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CoinflipGui.open(ctx.getSource().getPlayerOrException(), 0);
		return 1;
	}

	private static int coinflipCreate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		long amount = Args.money(ctx, "amount");
		return CoinflipGui.create(player, amount) ? 1 : 0;
	}

	private static int coinflipCancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CoinflipGui.cancelAll(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> chatStart() {
		return Commands.literal("start").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE))
			.executes(ctx -> chatStart(ctx, null))
			.then(Commands.argument("type", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(ChatGame.Type.values()).map(t -> t.command), b))
				.executes(ctx -> chatStart(ctx, StringArgumentType.getString(ctx, "type"))));
	}

	private static int chatStart(CommandContext<CommandSourceStack> ctx, String typeName) {
		if (MinigamesFeature.mega().busy()) {
			Msg.error(ctx.getSource(), "The MEGA-CHALLENGE is running – normal chat games are paused.");
			return 0;
		}
		ChatGame.Type type = null;
		if (typeName != null) {
			type = ChatGame.Type.parse(typeName);
			if (type == null) {
				Msg.error(ctx.getSource(), "Unknown type. Allowed: unscramble, math, fasttype, riddle");
				return 0;
			}
		}
		ChatGame game = MinigamesFeature.chatGames().start(type);
		Msg.success(ctx.getSource(), "Chat game started: " + game.type().label + " §8(chatgame type=" + game.type().id + ")");
		return 1;
	}

	private static int chatStop(CommandContext<CommandSourceStack> ctx) {
		if (MinigamesFeature.chatGames().active().isEmpty()) {
			Msg.error(ctx.getSource(), "No chat game is running.");
			return 0;
		}
		MinigamesFeature.chatGames().stop("§7The chat game was ended by an admin.");
		Msg.success(ctx.getSource(), "Chat game ended.");
		return 1;
	}

	private static int chatInfo(CommandContext<CommandSourceStack> ctx) {
		long now = System.currentTimeMillis();
		var game = MinigamesFeature.chatGames().active();
		long next = MinigamesFeature.chatGames().nextAt();
		String line = game.map(g -> "chatgame active=true type=" + g.type().id + " solution=" + g.solution() + " endsIn=" + Math.max(0, (g.endsAt() - now) / 1000) + "s")
			.orElse("chatgame active=false") + " nextIn=" + (next < 0 ? "-" : TimeUtil.formatDuration(next - now));
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return 1;
	}

	private static int chatStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		boolean on = Settings.get(player, Settings.CHATGAMES);
		if (on && MinigamesFeature.mega().busy()) {
			for (Component line : MinigamesFeature.mega().playerStatus(player.getUUID())) Msg.send(player, line);
			return 1;
		}
		if (!on) {
			Msg.send(player, Msg.prefixed("§7Chat games are §coff §7for you. ")
				.append(Msg.clickRun("§a[Turn on]", "/chatgame on", "§7Show chat games again")));
			return 1;
		}
		var game = MinigamesFeature.chatGames().active();
		if (game.isPresent()) {
			ChatGame g = game.get();
			Msg.send(player, Msg.prefixed("§e§l" + g.type().label + " §8» " + g.question()));
			for (int i = 1; i < g.hintsShown(); i++) Msg.send(player, Msg.prefixed("§e§lHint " + (i + 1) + " §8» §f" + g.hints().get(i)));
			Msg.info(player, "§f" + TimeUtil.formatDuration(g.endsAt() - System.currentTimeMillis()) + "§7 left – just type your answer in chat.");
		} else {
			long next = MinigamesFeature.chatGames().nextAt();
			Msg.send(player, Msg.prefixed("§7No chat game is running right now" + (next > 0 ? ", the next one starts in about §f"
				+ TimeUtil.formatDuration(next - System.currentTimeMillis()) + "§7" : "") + ". ")
				.append(Msg.clickRun("§8[Turn off]", "/chatgame off", "§7Hide chat games")));
			for (Component line : MinigamesFeature.mega().playerStatus(player.getUUID())) Msg.send(player, line);
		}
		return 1;
	}

	private static int chatToggle(CommandContext<CommandSourceStack> ctx, boolean value) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Settings.set(player.getUUID(), Settings.CHATGAMES, value);
		if (value) Msg.success(player, "Chat games turned on.");
		else Msg.info(player, "Chat games turned §coff§7 – you won't see them and can't take part.");
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> megaTree() {
		return Commands.literal("mega")
			.executes(MinigamesCommands::megaPlayerStatus)
			.then(Commands.literal("status").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::megaStatus))
			.then(megaStart("start", true, false))
			.then(megaStart("now", false, false))
			.then(megaStart("bonus", true, true))
			.then(Commands.literal("stop").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::megaStop))
			.then(Commands.literal("solution").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::megaSolution))
			.then(megaRulesTree("rules"));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> megaRulesTree(String literal) {
		return Commands.literal(literal).requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE))
			.executes(MinigamesCommands::megaRules)
			.then(Commands.literal("playtime")
				.then(Commands.argument("minutes", IntegerArgumentType.integer(0, MegaConfig.MAX_PLAYTIME_MINUTES))
					.executes(ctx -> megaSetRule(ctx, "playtime"))))
			.then(Commands.literal("ip")
				.then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> megaSetRule(ctx, "ip"))));
	}

	private static int megaRules(CommandContext<CommandSourceStack> ctx) {
		MegaConfig c = MinigamesFeature.config().megaChallenge;
		Msg.send(ctx.getSource(), Msg.legacy("§7mega rules minPlaytime=" + c.minPlaytimeMinutes + " onePerIp=" + c.onePerIp
			+ " §8(IP rule only for public addresses – no effect behind the playit tunnel)"));
		return 1;
	}

	/** E.g. playtime 0 for fresh e2e bots. */
	private static int megaSetRule(CommandContext<CommandSourceStack> ctx, String rule) {
		MegaConfig c = MinigamesFeature.config().megaChallenge;
		if (rule.equals("playtime")) c.minPlaytimeMinutes = IntegerArgumentType.getInteger(ctx, "minutes");
		else c.onePerIp = BoolArgumentType.getBool(ctx, "on");
		try {
			MinigamesFeature.CONFIG.save();
		} catch (java.io.IOException e) {
			Msg.error(ctx.getSource(), "Couldn't save minigames.json: " + e.getMessage());
		}
		return megaRules(ctx);
	}

	/** {@code bonus}: always announced and never a race. */
	private static LiteralArgumentBuilder<CommandSourceStack> megaStart(String literal, boolean announce, boolean bonus) {
		return Commands.literal(literal).requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE))
			.executes(ctx -> megaStart(ctx, null, null, announce, bonus))
			.then(Commands.argument("type", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(Arrays.stream(MegaChallenge.Type.values())
					.filter(t -> !bonus || t != MegaChallenge.Type.RACE).map(t -> t.command), b))
				.executes(ctx -> megaStart(ctx, StringArgumentType.getString(ctx, "type"), null, announce, bonus))
				.then(Commands.argument("variant", StringArgumentType.word())
					.suggests((ctx, b) -> {
						MegaChallenge.Type t = MegaChallenge.Type.parse(StringArgumentType.getString(ctx, "type"));
						return t == null ? b.buildFuture() : SharedSuggestionProvider.suggest(MegaGenerator.variants(t, MinigamesFeature.config().megaChallenge), b);
					})
					.executes(ctx -> megaStart(ctx, StringArgumentType.getString(ctx, "type"), StringArgumentType.getString(ctx, "variant"), announce, bonus))));
	}

	private static int megaStart(CommandContext<CommandSourceStack> ctx, String typeName, String variant, boolean announce, boolean bonus) {
		MegaChallenge.Type type = null;
		if (typeName != null) {
			type = MegaChallenge.Type.parse(typeName);
			if (type == null) {
				Msg.error(ctx.getSource(), "Unknown type. Allowed: math, trivia, race, riddle, unscramble");
				return 0;
			}
		}
		String error = MinigamesFeature.mega().start(type, variant, announce, bonus);
		if (error != null) {
			Msg.error(ctx.getSource(), error);
			return 0;
		}
		MegaChallenge.Prepared p = MinigamesFeature.mega().current();
		if (p == null) {
			Msg.error(ctx.getSource(), "MEGA-CHALLENGE cancelled: nobody online with chat games on who may take part (see /chatgame mega rules).");
			return 0;
		}
		Msg.success(ctx.getSource(), "MEGA-CHALLENGE started" + (announce ? " (announced)" : "") + (MinigamesFeature.mega().bonus() ? " [BONUS "
			+ dev.smpeconomy.core.Money.format(MinigamesFeature.mega().reward()) + "]" : "") + ": " + p.type().label
			+ (p.variantLabel().equalsIgnoreCase(p.type().label) ? "" : " – " + p.variantLabel())
			+ " §8(mega type=" + p.type().id + " variant=" + p.variant() + " id=" + MinigamesFeature.mega().id() + ")");
		return 1;
	}

	private static int megaStop(CommandContext<CommandSourceStack> ctx) {
		if (!MinigamesFeature.mega().stop("§7The MEGA-CHALLENGE was ended by an admin.")) {
			Msg.error(ctx.getSource(), "No MEGA-CHALLENGE is running.");
			return 0;
		}
		Msg.success(ctx.getSource(), "MEGA-CHALLENGE ended (no payout).");
		return 1;
	}

	private static int megaStatus(CommandContext<CommandSourceStack> ctx) {
		Msg.send(ctx.getSource(), Msg.legacy("§7" + MinigamesFeature.mega().statusLine()));
		return 1;
	}

	/**
	 * RCON only: the answer shows there but neither in the log nor in game, since the console writes its output to
	 * the log.
	 */
	private static int megaSolution(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (src.getEntity() != null || !"Rcon".equals(src.getTextName())) {
			Msg.error(src, "RCON only – the solution must not show up in game or in the log before the end.");
			return 0;
		}
		String line = MinigamesFeature.mega().solutionLine();
		if (line == null) {
			Msg.error(src, "No MEGA question is running.");
			return 0;
		}
		src.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static int megaPlayerStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!Settings.get(player, Settings.CHATGAMES)) {
			Msg.send(player, Msg.prefixed("§7Chat games (including the MEGA-CHALLENGE) are §coff §7for you. ")
				.append(Msg.clickRun("§a[Turn on]", "/chatgame on", "§7Show chat games again")));
			return 1;
		}
		for (Component line : MinigamesFeature.mega().playerStatus(player.getUUID())) Msg.send(player, line);
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> treasureTree(String name) {
		return Commands.literal(name)
			.requires(Perms.require(NODE_TREASURE))
			.executes(MinigamesCommands::treasureStatus)
			.then(Commands.literal("start").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::treasureStart))
			.then(Commands.literal("stop").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::treasureStop))
			.then(Commands.literal("info").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE)).executes(MinigamesCommands::treasureInfo))
			.then(Commands.literal("place").requires(Perms.requireAdmin(MinigamesFeature.ADMIN_NODE))
				.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MinigamesCommands::treasurePlace)));
	}

	private static int treasureStatus(CommandContext<CommandSourceStack> ctx) {
		var active = MinigamesFeature.treasure().active();
		if (active.isEmpty()) {
			Msg.info(ctx.getSource(), "No treasure hunt is running right now. One starts every few hours – watch out for the announcement!");
			return 1;
		}
		for (Component line : MinigamesFeature.treasure().status(active.get())) Msg.send(ctx.getSource(), line);
		return 1;
	}

	private static int treasureStart(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		boolean started = MinigamesFeature.treasure().startSearch(src.getServer(), msg -> Msg.info(src, "Treasure hunt: " + msg));
		if (started) Msg.info(src, "Looking for a hiding spot in the pregenerated area…");
		return started ? 1 : 0;
	}

	private static int treasureStop(CommandContext<CommandSourceStack> ctx) {
		TreasureHunt hunt = MinigamesFeature.treasure();
		if (hunt.active().isEmpty()) {
			hunt.abortSearch();
			Msg.error(ctx.getSource(), "No treasure hunt is running.");
			return 0;
		}
		hunt.cleanup(ctx.getSource().getServer(), "§6§lTREASURE HUNT §8» §7The treasure hunt was ended by an admin.");
		Msg.success(ctx.getSource(), "Treasure hunt ended, chest removed.");
		return 1;
	}

	private static int treasurePlace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		TreasureHunt.Active a = MinigamesFeature.treasure().place(ctx.getSource().getLevel(), pos, true);
		if (a == null) {
			Msg.error(ctx.getSource(), "There's no room there (block occupied or liquid).");
			return 0;
		}
		Msg.success(ctx.getSource(), "Treasure hidden at " + a.x + " " + a.y + " " + a.z + " §8(money=" + a.money + ")");
		return 1;
	}

	private static int treasureInfo(CommandContext<CommandSourceStack> ctx) {
		TreasureHunt hunt = MinigamesFeature.treasure();
		long now = System.currentTimeMillis();
		String line = hunt.active().map(a -> "treasure active=true x=" + a.x + " y=" + a.y + " z=" + a.z + " dim=" + a.dimension + " money=" + a.money
				+ " items=" + a.items.size() + " keys=" + a.commonKeys + "/" + a.rareKeys + " stage=" + a.stage + " endsIn=" + Math.max(0, (a.endsAt - now) / 1000) + "s")
			.orElse("treasure active=false") + " searching=" + hunt.isSearching()
			+ " nextIn=" + (hunt.nextAt() < 0 ? "-" : TimeUtil.formatDuration(hunt.nextAt() - now));
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Coinflips flips = MinigamesFeature.coinflips();
		MinigamesConfig.Coinflip cfg = MinigamesFeature.config().coinflip;
		Msg.send(src, Msg.legacy("§7coinflips open=" + flips.open().size() + " escrow=" + flips.escrow()
			+ " announceMinBet=" + cfg.announceMinBet + " announceCooldown=" + cfg.announceCooldownSeconds + "s"));
		for (Coinflips.Flip f : flips.open()) {
			Msg.send(src, Msg.legacy("§8  flip id=" + f.id + " creator=" + f.creatorName + " amount=" + f.amount));
		}
		chatInfo(ctx);
		treasureInfo(ctx);
		Msg.send(src, Msg.legacy("§7" + MinigamesFeature.mega().statusLine()));
		return 1;
	}

	private static int balance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownAccount(ctx, "player");
		long cents = EconomyService.balance(id);
		Msg.send(ctx.getSource(), Msg.legacy("§7" + EconomyService.nameOrUnknown(id) + " balance=" + cents + " §8(" + Money.format(cents) + ")"));
		return 1;
	}
}
