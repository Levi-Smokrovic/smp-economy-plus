package dev.smpeconomy.features.clans;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.LogEntry;
import dev.smpeconomy.features.clans.ClanData.Member;
import dev.smpeconomy.features.clans.ClanService.Res;

/**
 * {@code /clan ...} and {@code /cc <message>}. Player node {@code smpeconomy.clans} (allowed by default),
 * admin node {@code smpeconomy.admin.clans} (default OP 2): {@code /clan admin
 * disband|reset|dump|who|sync|orphan} ({@code orphan <account> treasury|player <name>}).
 */
public final class ClanCommands {
	public static final String NODE = "smpeconomy.clans";
	public static final String ADMIN_NODE = "smpeconomy.admin.clans";
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH); // Zone applied only at format time: the core.json timezone can change on reload
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH); // Zone applied only at format time: the core.json timezone can change on reload

	private ClanCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(root());
			dispatcher.register(literal("cc").requires(Perms.require(NODE))
				.executes(ctx -> usage(ctx, "/cc <message>"))
				.then(argument("message", StringArgumentType.greedyString())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.chat(p, StringArgumentType.getString(ctx, "message"))))));
		});
	}

	@FunctionalInterface
	private interface PlayerAction {
		boolean run(ServerPlayer player) throws CommandSyntaxException;
	}

	private static int withPlayer(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		return action.run(p) ? 1 : 0;
	}

	private static int usage(CommandContext<CommandSourceStack> ctx, String text) {
		Msg.error(ctx.getSource(), "Usage: §f" + text);
		return 0;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> root() {
		return literal("clan").requires(Perms.require(NODE))
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayer();
				if (p == null) return help(ctx);
				ClanGui.open(p);
				return 1;
			})
			.then(literal("help").executes(ClanCommands::help))
			.then(literal("create")
				.executes(ctx -> usage(ctx, "/clan create <name> <tag> [color]"))
				.then(argument("name", StringArgumentType.word())
					.then(argument("tag", StringArgumentType.word())
						.executes(ctx -> withPlayer(ctx, p -> ClanActions.create(p, str(ctx, "name"), str(ctx, "tag"), null)))
						.then(argument("color", StringArgumentType.word()).suggests(colors())
							.executes(ctx -> withPlayer(ctx, p -> ClanActions.create(p, str(ctx, "name"), str(ctx, "tag"), str(ctx, "color"))))))))
			.then(literal("invite")
				.executes(ctx -> usage(ctx, "/clan invite <player>"))
				.then(argument("player", StringArgumentType.word()).suggests(invitablePlayers())
					.executes(ctx -> withPlayer(ctx, p -> {
						ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(str(ctx, "player"));
						if (target == null) {
							Msg.error(p, "Player §f" + str(ctx, "player") + " §cisn't online.");
							return false;
						}
						return ClanActions.invite(p, target);
					}))))
			.then(literal("accept")
				.executes(ctx -> withPlayer(ctx, p -> ClanActions.accept(p, null)))
				.then(argument("clan", StringArgumentType.word()).suggests(myInvites())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.accept(p, str(ctx, "clan"))))))
			.then(literal("deny")
				.executes(ctx -> withPlayer(ctx, p -> ClanActions.deny(p, null)))
				.then(argument("clan", StringArgumentType.word()).suggests(myInvites())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.deny(p, str(ctx, "clan"))))))
			.then(literal("join")
				.executes(ctx -> usage(ctx, "/clan join <clan>"))
				.then(argument("clan", StringArgumentType.word()).suggests(joinableClans())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.join(p, str(ctx, "clan"))))))
			.then(literal("leave").executes(ctx -> withPlayer(ctx, ClanActions::leave)))
			.then(literal("kick")
				.executes(ctx -> usage(ctx, "/clan kick <player>"))
				.then(argument("player", StringArgumentType.word()).suggests(myMembers())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.kick(p, str(ctx, "player"))))))
			.then(literal("promote")
				.executes(ctx -> usage(ctx, "/clan promote <player>"))
				.then(argument("player", StringArgumentType.word()).suggests(myMembers())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.promote(p, str(ctx, "player"), false)))
					.then(literal("confirm").executes(ctx -> withPlayer(ctx, p -> ClanActions.promote(p, str(ctx, "player"), true))))))
			.then(literal("demote")
				.executes(ctx -> usage(ctx, "/clan demote <player>"))
				.then(argument("player", StringArgumentType.word()).suggests(myMembers())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.demote(p, str(ctx, "player"))))))
			.then(literal("disband")
				.executes(ctx -> withPlayer(ctx, ClanCommands::disbandRequest))
				.then(literal("confirm").executes(ctx -> withPlayer(ctx, ClanCommands::disbandConfirm))))
			.then(literal("info")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayer();
					Clan c = p == null ? null : ClanService.clanOf(p.getUUID()).orElse(null);
					if (c == null) {
						Msg.error(ctx.getSource(), "You're not in a clan. §7(/clan info <clan>)");
						return 0;
					}
					return info(ctx.getSource(), c);
				})
				.then(argument("clan", StringArgumentType.word()).suggests(allClans())
					.executes(ctx -> {
						Clan c = ClanService.find(str(ctx, "clan")).orElse(null);
						if (c == null) {
							Msg.error(ctx.getSource(), "Clan §f" + str(ctx, "clan") + " §cnot found.");
							return 0;
						}
						return info(ctx.getSource(), c);
					})))
			.then(literal("top").executes(ctx -> top(ctx.getSource())))
			.then(literal("sethome").executes(ctx -> withPlayer(ctx, ClanActions::setHome)))
			.then(literal("home").executes(ctx -> withPlayer(ctx, ClanActions::home)))
			.then(literal("bank")
				.executes(ctx -> withPlayer(ctx, p -> {
					ClanGui.openBank(p);
					return true;
				}))
				.then(literal("deposit")
					.executes(ctx -> usage(ctx, "/clan bank deposit <amount|all>"))
					.then(argument("amount", StringArgumentType.greedyString()).suggests(allSuggestion())
						.executes(ctx -> withPlayer(ctx, p -> {
							String in = str(ctx, "amount").trim();
							// Plain members get a confirmation dialog first, since they cannot withdraw again
							boolean all = isAll(in);
							long amount = all ? EconomyService.balance(p.getUUID()) : parseMoney(in);
							return ClanGui.depositOrConfirm(p, amount, all, null) != ClanGui.DepositStep.FAILED;
						}))))
				.then(literal("withdraw")
					.executes(ctx -> usage(ctx, "/clan bank withdraw <amount|all>"))
					.then(argument("amount", StringArgumentType.greedyString()).suggests(allSuggestion())
						.executes(ctx -> withPlayer(ctx, p -> {
							String in = str(ctx, "amount").trim();
							if (isAll(in)) return ClanActions.withdrawAll(p);
							return ClanActions.withdraw(p, parseMoney(in));
						}))))
				.then(literal("log").executes(ctx -> withPlayer(ctx, ClanCommands::bankLog))))
			.then(literal("chat")
				.executes(ctx -> withPlayer(ctx, ClanActions::toggleChat))
				.then(argument("message", StringArgumentType.greedyString())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.chat(p, str(ctx, "message"))))))
			.then(literal("open").executes(ctx -> withPlayer(ctx, ClanActions::toggleOpen)))
			.then(literal("color")
				.executes(ctx -> usage(ctx, "/clan color <" + String.join("|", ClanColor.ids()) + ">"))
				.then(argument("color", StringArgumentType.word()).suggests(colors())
					.executes(ctx -> withPlayer(ctx, p -> ClanActions.color(p, str(ctx, "color"))))))
			.then(admin());
	}

	private static LiteralArgumentBuilder<CommandSourceStack> admin() {
		return literal("admin").requires(Perms.requireAdmin(ADMIN_NODE))
			.then(literal("disband").then(argument("clan", StringArgumentType.word()).suggests(allClans()).executes(ctx -> {
				Clan c = ClanService.find(str(ctx, "clan")).orElse(null);
				if (c == null) {
					Msg.error(ctx.getSource(), "Clan not found.");
					return 0;
				}
				Res r = ClanService.dissolve(c);
				if (!r.ok()) {
					Msg.error(ctx.getSource(), r.message());
					return 0;
				}
				ClanActions.afterDissolve(c, "an admin", null);
				Msg.success(ctx.getSource(), r.message());
				return 1;
			})))
			.then(literal("reset").then(argument("player", StringArgumentType.word()).suggests(Args.knownPlayers()).executes(ctx -> {
				UUID id = Args.knownPlayer(ctx, "player");
				Clan c = ClanService.clanOf(id).orElse(null);
				boolean leader = c != null && c.role(id) == ClanData.Role.LEADER;
				Res r = ClanService.adminReset(id);
				if (!r.ok()) {
					Msg.info(ctx.getSource(), r.message());
					return 0;
				}
				if (leader) {
					ClanActions.afterDissolve(c, "an admin", null); // The leader hears about it too, possibly on next join
					if (r.amount() > 0) Msg.sendOrQueue(id, "The Clan Bank (" + Msg.moneyStr(r.amount()) + "§7) was paid out to you.");
				} else if (c != null) {
					Msg.sendOrQueue(id, "§cAn admin removed you from the clan " + c.display() + "§c.");
					ClanActions.notifyClan(c, "§f" + EconomyService.nameOrUnknown(id) + " §7was removed from the clan by an admin.", id);
				}
				ClanTeleport.cancel(id);
				Msg.success(ctx.getSource(), r.message());
				return 1;
			})))
			.then(literal("dump").then(argument("clan", StringArgumentType.word()).suggests(allClans()).executes(ctx -> {
				Clan c = ClanService.find(str(ctx, "clan")).orElse(null);
				if (c == null) {
					Msg.send(ctx.getSource(), Msg.legacy("§7clan notfound=" + str(ctx, "clan")));
					return 0;
				}
				Msg.send(ctx.getSource(), Msg.legacy("§7" + dump(ctx.getSource(), c)));
				return 1;
			})))
			.then(literal("who").then(argument("player", StringArgumentType.word()).suggests(Args.knownPlayers()).executes(ctx -> {
				UUID id = Args.knownPlayer(ctx, "player");
				String name = EconomyService.nameOrUnknown(id);
				Clan c = ClanService.clanOf(id).orElse(null);
				PlayerTeam team = ctx.getSource().getServer().getScoreboard().getPlayersTeam(name);
				Msg.send(ctx.getSource(), Msg.legacy("§7who name=" + name + " clan=" + (c == null ? "none" : c.id) + " role="
					+ (c == null ? "none" : c.role(id).name()) + " wallet=" + EconomyService.balance(id) + " team=" + (team == null ? "none" : team.getName())
					+ " chat=" + ClanService.chatToggled(id) + " teleport=" + ClanTeleport.isPending(id)
					+ " invites=" + ClanService.invitesFor(id, ClanActions.now()).size()));
				return 1;
			})))
			.then(literal("sync").executes(ctx -> {
				ClanTeams.syncAll(ctx.getSource().getServer());
				Msg.success(ctx.getSource(), "Clan teams synced (" + ClanService.all().size() + " clans).");
				return 1;
			}))
			.then(literal("orphan")
				.executes(ctx -> orphanList(ctx.getSource()))
				.then(argument("account", StringArgumentType.word()).suggests(orphanIds())
					.executes(ctx -> usage(ctx, "/clan admin orphan <account> <treasury|player <name>>"))
					.then(literal("treasury").executes(ctx -> orphanResolve(ctx, null)))
					.then(orphanToPlayer("player"))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> orphanToPlayer(String name) {
		return literal(name)
			.executes(ctx -> usage(ctx, "/clan admin orphan <account> player <name>"))
			.then(argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(ctx -> orphanResolve(ctx, Args.knownPlayer(ctx, "player"))));
	}

	/**
	 * Lists orphaned clan bank accounts, rescanning first. Lines: {@code orphan id=<uuid> name="..."
	 * balance=<cents>}.
	 */
	private static int orphanList(CommandSourceStack src) {
		ClanService.scanOrphanBanks();
		List<ClanService.Orphan> list = ClanService.orphans();
		if (list.isEmpty()) {
			Msg.info(src, "No orphaned Clan Bank accounts. §8(orphans=0)");
			return 1;
		}
		Msg.info(src, "§6Orphaned Clan Bank accounts §8(orphans=" + list.size() + ") §7– click to pay out:");
		for (ClanService.Orphan o : list) {
			String cmd = "/clan admin orphan " + o.id() + " ";
			Msg.send(src, Msg.legacy("§8 » §7orphan id=" + o.id() + " name=\"" + o.name() + "\" balance=" + o.balance() + " §8(" + Money.format(o.balance()) + ") ")
				.append(Msg.clickSuggest("§e[Treasury]", cmd + "treasury", "§7Pay the balance to the Treasury, delete the account"))
				.append(Msg.legacy(" "))
				.append(Msg.clickSuggest("§e[Player]", cmd + "player ", "§7Pay the balance to a player, delete the account")));
		}
		return list.size();
	}

	private static int orphanResolve(CommandContext<CommandSourceStack> ctx, UUID target) {
		UUID id;
		try {
			id = UUID.fromString(str(ctx, "account"));
		} catch (IllegalArgumentException e) {
			Msg.error(ctx.getSource(), "Invalid account ID. §7(/clan admin orphan)");
			return 0;
		}
		Res r = ClanService.resolveOrphan(id, target);
		if (!r.ok()) {
			Msg.error(ctx.getSource(), r.message());
			return 0;
		}
		if (target != null && r.amount() > 0) {
			Msg.sendOrQueue(target, "§7An admin paid you " + Msg.moneyStr(r.amount()) + " §7from an orphaned Clan Bank.");
		}
		Msg.success(ctx.getSource(), r.message());
		return 1;
	}

	/** Machine-readable line for admins and E2E. */
	static String dump(CommandSourceStack src, Clan c) {
		StringBuilder members = new StringBuilder();
		StringBuilder wallets = new StringBuilder();
		for (Map.Entry<UUID, Member> e : ClanService.sortedMembers(c)) {
			if (!members.isEmpty()) {
				members.append(',');
				wallets.append(',');
			}
			members.append(e.getValue().name).append(':').append(e.getValue().role.name());
			wallets.append(e.getValue().name).append(':').append(EconomyService.balance(e.getKey()));
		}
		PlayerTeam team = src.getServer().getScoreboard().getPlayerTeam(ClanTeams.teamName(c));
		List<String> teamPlayers = team == null ? List.of() : new ArrayList<>(team.getPlayers());
		teamPlayers.sort(String.CASE_INSENSITIVE_ORDER);
		String home = c.home == null ? "none" : c.home.dim + ";" + fmt(c.home.x) + ";" + fmt(c.home.y) + ";" + fmt(c.home.z);
		List<String> openJoin = new ArrayList<>();
		for (Map.Entry<UUID, Member> e : ClanService.sortedMembers(c)) {
			if (e.getValue().openJoin) openJoin.add(e.getValue().name);
		}
		return "clan id=" + c.id + " name=" + c.name + " tag=" + c.tag + " color=" + c.color + " open=" + c.open
			+ " bank=" + ClanService.bankBalance(c) + " leader=" + c.leaderName() + " members=" + members + " wallets=" + wallets
			+ " invites=" + c.invites.size() + " home=" + home + " log=" + c.log.size()
			+ " openJoin=" + (openJoin.isEmpty() ? "none" : String.join(",", openJoin))
			+ " team=" + (team == null ? "none" : team.getName())
			+ " prefix=\"" + (team == null ? "" : team.getPlayerPrefix().getString()) + "\""
			+ " ff=" + (team != null && team.isAllowFriendlyFire())
			+ " teamPlayers=" + String.join(",", teamPlayers);
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static String str(CommandContext<CommandSourceStack> ctx, String name) {
		return StringArgumentType.getString(ctx, name);
	}

	private static boolean isAll(String in) {
		return in.equalsIgnoreCase("all");
	}

	private static long parseMoney(String in) throws CommandSyntaxException {
		OptionalLong v = Money.parse(in);
		if (v.isEmpty()) throw Args.INVALID_AMOUNT.create();
		return v.getAsLong();
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack s = ctx.getSource();
		Msg.info(s, "§6§lClans §8– §7Commands:");
		String[] lines = {
			"§e/clan §7– overview (GUI)",
			"§e/clan create <name> <tag> [color] §7– create a clan (" + Msg.moneyStr(ClanService.config().creationFee) + ")",
			"§e/clan invite|kick|promote|demote <player>",
			"§e/clan accept|deny [clan] §7· §e/clan join <clan> §7· §e/clan leave",
			"§e/clan info [clan] §7· §e/clan top §7· §e/clan disband",
			"§e/clan sethome §7· §e/clan home §7(" + ClanService.config().teleportDelaySeconds + "s, don't move)",
			"§e/clan bank [deposit|withdraw <amount|all>|log]",
			"§e/cc <message> §7· §e/clan chat §7(toggle) · §e/clan open §7· §e/clan color <color>",
		};
		for (String l : lines) Msg.send(s, Msg.legacy("§8 » " + l));
		return 1;
	}

	static int info(CommandSourceStack src, Clan c) {
		Msg.info(src, "§6§lClan " + c.display());
		Msg.send(src, Msg.legacy("§8 » §7Leader: §f" + c.leaderName() + " §8· §7Members: §f" + c.members.size() + "/" + ClanService.config().maxMembers
			+ " §8· " + (c.open ? "§aopen" : "§cinvite only")));
		Msg.send(src, Msg.legacy("§8 » §7Clan Bank: " + Msg.moneyStr(ClanService.bankBalance(c)) + " §8· §7Home: " + (c.home == null ? "§cnot set" : "§aset")
			+ " §8· §7Founded: §f" + DATE.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(c.createdAt))));
		List<String> names = new ArrayList<>();
		for (Map.Entry<UUID, Member> e : ClanService.sortedMembers(c)) {
			boolean online = src.getServer().getPlayerList().getPlayer(e.getKey()) != null;
			names.add(e.getValue().role.symbol + (online ? "§a" : "§7") + e.getValue().display());
		}
		Msg.send(src, Msg.legacy("§8 » §7Members: " + String.join("§8, ", names)));
		return 1;
	}

	static int top(CommandSourceStack src) {
		List<ClanService.TopEntry> top = ClanService.top();
		if (top.isEmpty()) {
			Msg.info(src, "There are no clans yet. §e/clan create <name> <tag>");
			return 1;
		}
		Msg.info(src, "§6§lClan Leaderboard §8(Bank + members' wealth)");
		for (int i = 0; i < Math.min(10, top.size()); i++) {
			ClanService.TopEntry e = top.get(i);
			Msg.send(src, Msg.legacy("§8 » §e#" + (i + 1) + " " + e.clan().display() + " §8– " + Msg.moneyStr(e.total())
				+ " §8(Bank " + Money.formatShort(e.bank()) + ", " + e.clan().members.size() + (e.clan().members.size() == 1 ? " member)" : " members)")));
		}
		ServerPlayer p = src.getPlayer();
		if (p != null) {
			ClanService.clanOf(p.getUUID()).ifPresent(own -> {
				for (int i = 0; i < top.size(); i++) {
					if (top.get(i).clan() == own && i >= 10) {
						Msg.send(src, Msg.legacy("§8 » §7Your clan: §e#" + (i + 1) + " " + own.display() + " §8– " + Msg.moneyStr(top.get(i).total())));
					}
				}
			});
		}
		return 1;
	}

	private static boolean bankLog(ServerPlayer p) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null) {
			Msg.error(p, "You're not in a clan.");
			return false;
		}
		Msg.info(p, "§6Clan Bank " + c.coloredTag() + " §7– balance " + Msg.moneyStr(ClanService.bankBalance(c)));
		if (c.log.isEmpty()) {
			Msg.send(p, Msg.legacy("§8 » §7No transactions yet."));
			return true;
		}
		for (int i = c.log.size() - 1; i >= 0; i--) Msg.send(p, Msg.legacy("§8 » " + logLine(c.log.get(i))));
		return true;
	}

	static String logLine(LogEntry e) {
		boolean in = !LogEntry.WITHDRAW.equals(e.type);
		return "§8" + TIME.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(e.time)) + " " + (in ? "§a+" : "§c-") + Money.format(e.amount)
			+ " §7" + e.label() + " §f" + e.name + " §8(balance " + Money.formatShort(e.balanceAfter) + ")";
	}

	private static boolean disbandRequest(ServerPlayer p) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null || c.role(p.getUUID()) != ClanData.Role.LEADER) {
			return ClanActions.report(p, ClanService.disband(p.getUUID())); // Returns the matching error message, changes nothing
		}
		ClanActions.requestConfirm(p.getUUID(), "disband:" + c.id);
		Msg.send(p, Msg.prefixed("§cReally disband the clan " + c.display() + "§c? §7The Clan Bank (" + Msg.moneyStr(ClanService.bankBalance(c)) + ") goes to you. ")
			.append(Msg.clickRun("§c§l[Disband]", "/clan disband confirm", "§7Disband for good (valid for 30s)")));
		return true;
	}

	private static boolean disbandConfirm(ServerPlayer p) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null || !ClanActions.consumeConfirm(p.getUUID(), "disband:" + c.id)) {
			Msg.error(p, "Nothing to confirm – use §f/clan disband§c first.");
			return false;
		}
		return ClanActions.disband(p);
	}

	private static SuggestionProvider<CommandSourceStack> colors() {
		return (ctx, b) -> SharedSuggestionProvider.suggest(ClanColor.ids(), b);
	}

	private static SuggestionProvider<CommandSourceStack> allSuggestion() {
		return (ctx, b) -> SharedSuggestionProvider.suggest(List.of("all"), b);
	}

	private static SuggestionProvider<CommandSourceStack> orphanIds() {
		return (ctx, b) -> SharedSuggestionProvider.suggest(ClanService.orphans().stream().map(o -> o.id().toString()).toList(), b);
	}

	private static SuggestionProvider<CommandSourceStack> allClans() {
		return (ctx, b) -> SharedSuggestionProvider.suggest(ClanService.all().stream().map(c -> c.name).toList(), b);
	}

	private static SuggestionProvider<CommandSourceStack> joinableClans() {
		return (ctx, b) -> {
			List<String> names = new ArrayList<>();
			for (Clan c : ClanService.openClans()) names.add(c.name);
			ServerPlayer p = ctx.getSource().getPlayer();
			if (p != null) {
				for (Clan c : ClanService.invitesFor(p.getUUID(), ClanActions.now())) {
					if (!names.contains(c.name)) names.add(c.name);
				}
			}
			return SharedSuggestionProvider.suggest(names, b);
		};
	}

	private static SuggestionProvider<CommandSourceStack> myInvites() {
		return (ctx, b) -> {
			ServerPlayer p = ctx.getSource().getPlayer();
			if (p == null) return b.buildFuture();
			return SharedSuggestionProvider.suggest(ClanService.invitesFor(p.getUUID(), ClanActions.now()).stream().map(c -> c.name).toList(), b);
		};
	}

	private static SuggestionProvider<CommandSourceStack> myMembers() {
		return (ctx, b) -> {
			ServerPlayer p = ctx.getSource().getPlayer();
			Clan c = p == null ? null : ClanService.clanOf(p.getUUID()).orElse(null);
			if (c == null) return b.buildFuture();
			List<String> names = new ArrayList<>();
			for (Map.Entry<UUID, Member> e : c.members.entrySet()) {
				if (!e.getKey().equals(p.getUUID())) names.add(e.getValue().name);
			}
			return SharedSuggestionProvider.suggest(names, b);
		};
	}

	private static SuggestionProvider<CommandSourceStack> invitablePlayers() {
		return (ctx, b) -> {
			List<String> names = new ArrayList<>();
			for (ServerPlayer o : ctx.getSource().getServer().getPlayerList().getPlayers()) {
				if (ClanService.clanOf(o.getUUID()).isEmpty()) names.add(o.getGameProfile().name());
			}
			return SharedSuggestionProvider.suggest(names, b);
		};
	}
}
