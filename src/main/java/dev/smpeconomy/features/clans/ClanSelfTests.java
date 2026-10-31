package dev.smpeconomy.features.clans;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Data;
import dev.smpeconomy.features.clans.ClanData.Home;
import dev.smpeconomy.features.clans.ClanData.LogEntry;
import dev.smpeconomy.features.clans.ClanData.Member;
import dev.smpeconomy.features.clans.ClanData.Role;
import dev.smpeconomy.features.clans.ClanService.Code;
import dev.smpeconomy.features.clans.ClanService.Res;

/**
 * No GUI clicks. Test player names contain "-", so they can never collide with real players or their team
 * entries; {@link ClanService#purgeTestPlayer} cleans up their clans.
 */
final class ClanSelfTests {
	private static final String F = "clans";

	private ClanSelfTests() {}

	static void register() {
		SelfTests.register(F, "lifecycle", ClanSelfTests::lifecycle);
		SelfTests.register(F, "role-rights", ClanSelfTests::roles);
		SelfTests.register(F, "bank-conservation", ClanSelfTests::bank);
		SelfTests.register(F, "disband-payout", ClanSelfTests::disband);
		SelfTests.register(F, "team-sync", ClanSelfTests::teams);
		SelfTests.register(F, "validation-fee", ClanSelfTests::validation);
		SelfTests.register(F, "teleport", ClanSelfTests::teleport);
		SelfTests.register(F, "home-lock-open", ClanSelfTests::homeLock);
		SelfTests.register(F, "chat", ClanSelfTests::chat);
		SelfTests.register(F, "persistence-repair", ClanSelfTests::persistence);
		SelfTests.register(F, "leaderboard", ClanSelfTests::top);
		SelfTests.register(F, "orphaned-bank", ClanSelfTests::orphans);
	}

	private static FakePlayer player(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.ensureAccount(p.getUUID(), name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static long fee() {
		return ClanService.config().creationFee;
	}

	private static long bal(FakePlayer p) {
		return EconomyService.balance(p.getUUID());
	}

	private static String freeName(String base) {
		String n = base;
		for (int i = 1; ClanService.byName(n).isPresent(); i++) n = base + i;
		return n;
	}

	private static String freeTag(String base) {
		String t = base;
		for (int i = 1; ClanService.byTag(t).isPresent(); i++) t = base + i;
		return t;
	}

	private static Clan create(FakePlayer leader, String baseName, String baseTag, String color) {
		Res r = ClanService.create(leader.getUUID(), leader.getGameProfile().name(), freeName(baseName), freeTag(baseTag), color, now());
		Assert.isTrue(r.ok(), "create: " + r.message());
		return r.clan();
	}

	private static void ok(Res r, String what) {
		Assert.isTrue(r.ok(), what + ": " + r.code() + " " + r.message());
	}

	private static void code(Code expected, Res r, String what) {
		Assert.eq(expected, r.code(), what + " (" + r.message() + ")");
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private static void join(Clan c, FakePlayer leader, FakePlayer p) {
		ok(ClanService.invite(leader.getUUID(), p.getUUID(), p.getGameProfile().name(), now()), "invite " + p.getGameProfile().name());
		ok(ClanService.accept(p.getUUID(), p.getGameProfile().name(), c.name, now()), "accept " + p.getGameProfile().name());
	}

	private static void lifecycle(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer lead = player(ctx, "Cl-Lead", fee() + 100_000);
		FakePlayer a = player(ctx, "Cl-A", 0);
		FakePlayer b = player(ctx, "Cl-B", 0);
		UUID L = lead.getUUID(), A = a.getUUID(), B = b.getUUID();
		Clan c = create(lead, "TClanLife", "TL", "red");
		Assert.eq(Role.LEADER, c.role(L), "founder is leader");
		Assert.isTrue(ClanService.clanOf(L).orElse(null) == c, "clanOf(Leader)");
		code(Code.ALREADY_IN_CLAN, ClanService.create(L, "Cl-Lead", freeName("TClanLife"), freeTag("TX"), null, now), "second clan");

		code(Code.NOT_INVITED, ClanService.accept(A, "Cl-A", null, now), "accept without invite");
		code(Code.NOT_INVITED, ClanService.join(A, "Cl-A", c.name, now), "join a closed clan");
		code(Code.NOT_FOUND, ClanService.join(A, "Cl-A", "nonexistent-xyz", now), "unknown clan");

		ok(ClanService.invite(L, A, "Cl-A", now), "invite");
		Assert.isTrue(ClanService.isInvited(c, A, now), "invited");
		ok(ClanService.invite(L, A, "Cl-A", now), "renew invite");
		Assert.eq(1, ClanService.invitesFor(A, now).size(), "renewal does not duplicate");
		ok(ClanService.deny(A, null, now), "decline");
		Assert.isFalse(ClanService.isInvited(c, A, now), "no invite after decline");
		code(Code.NOT_INVITED, ClanService.deny(A, null, now), "decline again");

		ok(ClanService.invite(L, A, "Cl-A", now), "invite 2");
		ok(ClanService.accept(A, "Cl-A", null, now), "accept");
		Assert.eq(Role.MEMBER, c.role(A), "new member");
		Assert.isTrue(c.invites.isEmpty(), "invite used up");
		code(Code.ALREADY_IN_CLAN, ClanService.accept(A, "Cl-A", null, now), "accept twice");
		code(Code.ALREADY_IN_CLAN, ClanService.invite(L, A, "Cl-A", now), "invite a member");
		code(Code.SELF, ClanService.invite(L, L, "Cl-Lead", now), "invite self");

		// Expiry
		long expiry = ClanService.config().inviteExpiryMinutes * 60_000L;
		ok(ClanService.invite(L, B, "Cl-B", now), "invite B");
		code(Code.NOT_INVITED, ClanService.accept(B, "Cl-B", null, now + expiry + 1), "expired invite");
		// Check only the test clan: the global variant would also delete real invites of other clans
		Assert.isTrue(ClanService.purgeExpired(c, now + expiry + 1), "expiry cleans up");
		Assert.isFalse(c.invites.containsKey(B), "expired invite removed");

		ok(ClanService.toggleOpen(L), "open");
		ok(ClanService.join(B, "Cl-B", c.tag.toLowerCase(), now), "open join (by tag, lowercase)");
		Assert.eq(3, c.members.size(), "3 members");
		ok(ClanService.leave(B), "leave");
		Assert.isTrue(ClanService.clanOf(B).isEmpty(), "B without clan");

		ok(ClanService.kick(L, A), "kick");
		Assert.isTrue(ClanService.clanOf(A).isEmpty(), "A without clan");
		code(Code.NOT_MEMBER, ClanService.kick(L, A), "kick a non-member");
		code(Code.LEADER_CANNOT_LEAVE, ClanService.leave(L), "leader leaves");
		code(Code.NOT_IN_CLAN, ClanService.leave(A), "leave without clan");

		ClanConfig cfg = ClanService.config();
		int oldMax = cfg.maxMembers;
		ctx.cleanup(() -> ClanService.config().maxMembers = oldMax);
		cfg.maxMembers = 2;
		join(c, lead, a);
		code(Code.CLAN_FULL, ClanService.invite(L, B, "Cl-B", now), "full: invite");
		code(Code.CLAN_FULL, ClanService.join(B, "Cl-B", c.name, now), "full: open join");
		cfg.maxMembers = oldMax;
		ok(ClanService.join(B, "Cl-B", c.name, now), "room again");

		FakePlayer other = player(ctx, "Cl-Other", fee());
		Clan c2 = create(other, "TClanOther", "TO", null);
		code(Code.ALREADY_IN_CLAN, ClanService.invite(other.getUUID(), A, "Cl-A", now), "invite a player in another clan");
		Assert.eq(ClanColor.orDefault(ClanService.config().defaultColor).id, c2.color, "default color");
		ctx.detail("invite, decline, accept, expiry, open, leave, kick, full ok");
	}

	private static void roles(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer lead = player(ctx, "Cl-Lead", fee());
		FakePlayer off = player(ctx, "Cl-Off", 0);
		FakePlayer mem = player(ctx, "Cl-Mem", 0);
		FakePlayer out = player(ctx, "Cl-Out", 0);
		UUID L = lead.getUUID(), O = off.getUUID(), M = mem.getUUID(), X = out.getUUID();
		Clan c = create(lead, "TClanRoles", "TR", null);
		join(c, lead, off);
		join(c, lead, mem);
		Home home = new Home();

		// A member may do nothing administrative
		code(Code.NO_PERMISSION, ClanService.invite(O, X, "Cl-Out", now), "member invites");
		code(Code.NO_PERMISSION, ClanService.kick(M, O), "member kicks");
		code(Code.NO_PERMISSION, ClanService.promote(M, O, false), "member promotes");
		code(Code.NO_PERMISSION, ClanService.demote(M, O), "member demotes");
		code(Code.NO_PERMISSION, ClanService.setHome(M, home), "member sets home");
		code(Code.NO_PERMISSION, ClanService.withdraw(M, "Cl-Mem", 1, now), "member withdraws");
		code(Code.NO_PERMISSION, ClanService.toggleOpen(M), "member opens");
		code(Code.NO_PERMISSION, ClanService.setColor(M, "blue"), "member changes color");
		code(Code.NO_PERMISSION, ClanService.disband(M), "member disbands");
		Assert.isTrue(c.home == null && !c.open && c.members.size() == 3, "nothing changed");

		ok(ClanService.promote(L, O, false), "to officer");
		Assert.eq(Role.OFFICER, c.role(O), "officer");
		ok(ClanService.invite(O, X, "Cl-Out", now), "officer invites");
		ok(ClanService.setHome(O, home), "officer sets home");
		code(Code.BANK_INSUFFICIENT, ClanService.withdraw(O, "Cl-Off", 100, now), "officer withdraws (empty, but allowed)");
		code(Code.NO_PERMISSION, ClanService.kick(O, L), "officer kicks leader");
		code(Code.NO_PERMISSION, ClanService.promote(O, M, false), "officer promotes");
		code(Code.NO_PERMISSION, ClanService.demote(O, M), "officer demotes");
		code(Code.NO_PERMISSION, ClanService.toggleOpen(O), "officer opens");
		code(Code.NO_PERMISSION, ClanService.disband(O), "officer disbands");
		code(Code.SELF, ClanService.kick(O, O), "kick self");
		ok(ClanService.kick(O, M), "officer kicks member");
		Assert.isTrue(ClanService.clanOf(M).isEmpty(), "member out");

		join(c, lead, mem);
		ok(ClanService.promote(L, M, false), "second officer");
		code(Code.NO_PERMISSION, ClanService.kick(M, O), "officer kicks officer");
		ok(ClanService.demote(L, M), "demote");
		Assert.eq(Role.MEMBER, c.role(M), "member again");
		code(Code.ALREADY_ROLE, ClanService.demote(L, M), "demote a member");
		code(Code.SELF, ClanService.demote(L, L), "leader demotes self");
		code(Code.SELF, ClanService.promote(L, L, false), "leader promotes self");
		code(Code.NOT_MEMBER, ClanService.promote(L, X, false), "promote a stranger");

		// Leadership handover only with confirmation
		code(Code.NEEDS_CONFIRM, ClanService.promote(L, O, false), "handover without confirmation");
		Assert.eq(Role.LEADER, c.role(L), "unchanged without confirmation");
		Assert.eq(Role.OFFICER, c.role(O), "unchanged without confirmation (officer)");
		ok(ClanService.promote(L, O, true), "handover");
		Assert.eq(Role.LEADER, c.role(O), "new leader");
		Assert.eq(Role.OFFICER, c.role(L), "old leader is officer");
		Assert.eq(1, c.count(Role.LEADER), "exactly one leader");
		ok(ClanService.leave(L), "old leader can leave");
		ok(ClanService.setColor(O, "blue"), "new leader changes color");
		code(Code.INVALID_COLOR, ClanService.setColor(O, "black"), "unknown color");
		Assert.eq(ClanColor.BLUE.id, c.color, "color set (stored as the existing id)");
		ctx.detail("member, officer and leader permissions and handover ok");
	}

	private static void bank(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer lead = player(ctx, "Cl-Lead", fee() + 100_000);
		FakePlayer mem = player(ctx, "Cl-Mem", 50_000);
		FakePlayer out = player(ctx, "Cl-Out", 10_000);
		UUID L = lead.getUUID(), M = mem.getUUID();
		Clan c = create(lead, "TClanBank", "TB", null);
		join(c, lead, mem);
		Assert.isTrue(EconomyService.isSystemAccount(c.bank), "clan bank is a system account");
		Assert.isTrue(EconomyService.top().stream().noneMatch(e -> e.id().equals(c.bank)), "clan bank not in /baltop");
		long total0 = EconomyService.totalMoney();
		long sum0 = bal(lead) + bal(mem) + ClanService.bankBalance(c);

		ok(ClanService.deposit(M, "Cl-Mem", 20_000, now), "member deposits");
		Assert.eq(30_000, bal(mem), "member -$200");
		Assert.eq(20_000, ClanService.bankBalance(c), "bank +$200");
		code(Code.INSUFFICIENT_FUNDS, ClanService.deposit(M, "Cl-Mem", 30_001, now), "deposit too much");
		code(Code.INVALID_AMOUNT, ClanService.deposit(M, "Cl-Mem", 0, now), "deposit 0");
		code(Code.INVALID_AMOUNT, ClanService.deposit(M, "Cl-Mem", -5, now), "deposit negative");
		code(Code.INVALID_AMOUNT, ClanService.withdraw(L, "Cl-Lead", 0, now), "withdraw 0");
		code(Code.NOT_IN_CLAN, ClanService.deposit(out.getUUID(), "Cl-Out", 100, now), "stranger deposits");
		Assert.eq(30_000, bal(mem), "failures change nothing (member)");
		Assert.eq(20_000, ClanService.bankBalance(c), "failures change nothing (bank)");
		Assert.eq(10_000, bal(out), "stranger unchanged");

		ok(ClanService.deposit(L, "Cl-Lead", 100_000, now), "leader deposits");
		code(Code.NO_PERMISSION, ClanService.withdraw(M, "Cl-Mem", 1, now), "member withdraws");
		code(Code.BANK_INSUFFICIENT, ClanService.withdraw(L, "Cl-Lead", 120_001, now), "more than bank");
		ok(ClanService.withdraw(L, "Cl-Lead", 70_000, now), "leader withdraws");
		ok(ClanService.deposit(M, "Cl-Mem", 30_000, now), "member deposits the rest");
		Assert.eq(0, bal(mem), "member empty");
		Assert.eq(80_000, ClanService.bankBalance(c), "bank $800");
		Assert.eq(sum0, bal(lead) + bal(mem) + ClanService.bankBalance(c), "total of player + bank preserved");
		Assert.eq(total0, EconomyService.totalMoney(), "total money unchanged");

		Assert.eq(4, c.log.size(), "4 bookings in the log");
		LogEntry w = c.log.get(2);
		Assert.eq(LogEntry.WITHDRAW, w.type, "3rd booking = withdrawal");
		Assert.eq(70_000, w.amount, "withdrawal amount");
		Assert.eq(50_000, w.balanceAfter, "balance after withdrawal");
		Assert.eq("Cl-Lead", w.name, "name in the log");

		// Log capped at 20
		for (int i = 0; i < 25; i++) ok(ClanService.deposit(L, "Cl-Lead", 1, now), "mini deposit " + i);
		Assert.eq(ClanService.LOG_SIZE, c.log.size(), "log max 20");
		Assert.eq(80_025, c.log.get(c.log.size() - 1).balanceAfter, "last entry current");

		// Overflow: leader account full, withdrawal fails with no change
		long leadBal = bal(lead);
		EconomyService.set(L, Money.MAX);
		code(Code.LIMIT_EXCEEDED, ClanService.withdraw(L, "Cl-Lead", 1, now), "withdraw to a full account");
		Assert.eq(80_025, ClanService.bankBalance(c), "bank unchanged after overflow");
		EconomyService.set(L, leadBal);
		ctx.detail("deposits, withdrawals, failures, permissions, log and overflow: total preserved");
	}

	private static void disband(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer lead = player(ctx, "Cl-Lead", fee() * 3 + 10_000);
		FakePlayer mem = player(ctx, "Cl-Mem", 40_000);
		UUID L = lead.getUUID(), M = mem.getUUID();
		Clan c = create(lead, "TClanBye", "TY", null);
		int id = c.id;
		UUID bank = c.bank;
		join(c, lead, mem);
		ok(ClanService.deposit(M, "Cl-Mem", 40_000, now), "deposit member");
		ok(ClanService.deposit(L, "Cl-Lead", 10_000, now), "deposit leader");
		ClanService.toggleChat(M);
		long leadBefore = bal(lead);
		long total0 = EconomyService.totalMoney();
		long treasury0 = Treasury.balance();
		Scoreboard sb = ctx.server().getScoreboard();
		Assert.notNull(sb.getPlayerTeam(ClanTeams.teamName(c)), "team exists");

		code(Code.NO_PERMISSION, ClanService.disband(M), "member disbands");
		Assert.isTrue(ClanService.byId(id).isPresent(), "clan still there");
		Res r = ClanService.disband(L);
		ok(r, "disband");
		Assert.eq(50_000, r.amount(), "amount paid");
		Assert.eq(leadBefore + 50_000, bal(lead), "bank to leader");
		Assert.eq(0, bal(mem), "member gets nothing");
		Assert.isFalse(EconomyService.hasAccount(bank), "bank account deleted");
		Assert.isFalse(EconomyService.isSystemAccount(bank), "system account unregistered");
		Assert.isTrue(ClanService.byId(id).isEmpty(), "clan removed");
		Assert.isTrue(ClanService.clanOf(L).isEmpty() && ClanService.clanOf(M).isEmpty(), "no memberships left");
		Assert.isFalse(ClanService.chatToggled(M), "chat mode off");
		Assert.isTrue(sb.getPlayerTeam("tri_clan_" + id) == null, "team removed");
		Assert.eq(total0, EconomyService.totalMoney(), "no money created or lost");
		Assert.eq(treasury0, Treasury.balance(), "treasury unchanged (fee not refunded)");
		code(Code.NOT_IN_CLAN, ClanService.disband(L), "disband twice");

		Clan e = create(lead, "TClanEmpty", "TE", null);
		Res r2 = ClanService.disband(L);
		ok(r2, "disband an empty clan");
		Assert.eq(0, r2.amount(), "nothing paid");
		Assert.isFalse(EconomyService.hasAccount(e.bank), "empty account deleted");

		// Damaged: no leader, so the bank goes to the treasury (nothing vanishes)
		Clan d = create(lead, "TClanNoLead", "TN", null);
		ok(ClanService.deposit(L, "Cl-Lead", 7_000, now), "deposit");
		long t1 = Treasury.balance();
		long lead1 = bal(lead);
		d.members.get(L).role = Role.MEMBER;
		ok(ClanService.dissolve(d), "disband without leader");
		Assert.eq(t1 + 7_000, Treasury.balance(), "bank without leader to the treasury");
		Assert.eq(lead1, bal(lead), "member gets nothing");
		Assert.isFalse(EconomyService.hasAccount(d.bank), "account deleted");
		ctx.detail("$500 paid to leader, account, team and clan removed, money preserved");
	}

	private static void teams(TestContext ctx) {
		ctx.preserveTreasury();
		Scoreboard sb = ctx.server().getScoreboard();
		String foreignName = "tri_selftest_foreign";
		String staleName = ClanTeams.PREFIX + "999999999";
		ctx.cleanup(() -> {
			PlayerTeam f = sb.getPlayerTeam(foreignName);
			if (f != null) sb.removePlayerTeam(f);
			PlayerTeam s = sb.getPlayerTeam(staleName);
			if (s != null) sb.removePlayerTeam(s);
		});
		FakePlayer lead = player(ctx, "Cl-Lead", fee());
		FakePlayer mem = player(ctx, "Cl-Mem", 0);
		FakePlayer mem2 = player(ctx, "Cl-Mem2", 0);
		UUID L = lead.getUUID(), M = mem.getUUID();
		Clan c = create(lead, "TClanTeam", "TT", "red");
		PlayerTeam t = sb.getPlayerTeam(ClanTeams.teamName(c));
		Assert.notNull(t, "Team tri_clan_" + c.id);
		Assert.eq("[" + c.tag + "] ", t.getPlayerPrefix().getString(), "prefix");
		Assert.eq(TextColor.fromLegacyFormat(ChatFormatting.RED), t.getPlayerPrefix().getSiblings().get(1).getStyle().getColor(), "tag color red");
		Assert.isFalse(t.isAllowFriendlyFire(), "friendly fire off");
		Assert.isTrue(sb.getPlayersTeam("Cl-Lead") == t, "leader in team");

		join(c, lead, mem);
		Assert.isTrue(t.getPlayers().contains("Cl-Mem"), "member in team");
		ok(ClanService.setColor(L, "blue"), "color");
		Assert.eq(TextColor.fromLegacyFormat(ChatFormatting.BLUE), t.getPlayerPrefix().getSiblings().get(1).getStyle().getColor(), "tag color blue");
		Assert.eq(Component.literal(c.name), t.getDisplayName(), "display name = clan name");

		ok(ClanService.leave(M), "leave");
		Assert.isFalse(t.getPlayers().contains("Cl-Mem"), "not in team after leaving");
		Assert.isTrue(sb.getPlayersTeam("Cl-Mem") == null, "no team left");

		ClanService.updateName(L, "Cl-Lead2");
		Assert.isTrue(t.getPlayers().contains("Cl-Lead2") && !t.getPlayers().contains("Cl-Lead"), "name change in team");
		ClanService.updateName(L, "Cl-Lead");
		Assert.isTrue(t.getPlayers().contains("Cl-Lead") && !t.getPlayers().contains("Cl-Lead2"), "name back");

		// Someone else takes over a member's old name: entry cleared, team entry gone
		join(c, lead, mem);
		Assert.isTrue(t.getPlayers().contains("Cl-Mem"), "member back in team");
		ClanService.claimName(UUID.nameUUIDFromBytes("clans-selftest-namethief".getBytes()), "cl-mem");
		Assert.eq("", c.member(M).name, "old name cleared");
		Assert.eq("?", c.member(M).display(), "display ?");
		Assert.isFalse(t.getPlayers().contains("Cl-Mem"), "new name owner gets no prefix");
		ClanService.claimName(M, "Cl-Mem");
		Assert.eq("", c.member(M).name, "own name not cleared (only set on join)");
		ClanService.updateName(M, "Cl-Mem");
		Assert.isTrue(t.getPlayers().contains("Cl-Mem"), "back in team after join");
		ok(ClanService.leave(M), "leave 2");

		// A foreign team is respected
		PlayerTeam foreign = sb.getPlayerTeam(foreignName);
		if (foreign == null) foreign = sb.addPlayerTeam(foreignName);
		sb.addPlayerToTeam("Cl-Mem2", foreign);
		join(c, lead, mem2);
		boolean respect = ClanService.config().respectForeignTeams;
		Assert.isTrue((sb.getPlayersTeam("Cl-Mem2") == foreign) == respect, "foreign team (respectForeignTeams=" + respect + ")");

		// Manually removed: syncAll restores; an orphaned tri_clan_ team gets removed
		sb.removePlayerFromTeam("Cl-Lead", t);
		if (sb.getPlayerTeam(staleName) == null) sb.addPlayerTeam(staleName);
		ClanTeams.syncAll(ctx.server());
		Assert.isTrue(sb.getPlayersTeam("Cl-Lead") == t, "syncAll restores member");
		Assert.isTrue(sb.getPlayerTeam(staleName) == null, "orphaned team removed");
		Assert.isTrue(sb.getPlayerTeam(foreignName) != null, "foreign team stays");

		// Player without a clan in an old clan team: cleanupPlayer
		sb.addPlayerToTeam("Cl-Mem", t);
		ClanTeams.cleanupPlayer("Cl-Mem");
		Assert.isTrue(sb.getPlayersTeam("Cl-Mem") == null, "cleanupPlayer removes non-member");

		ok(ClanService.disband(L), "disband");
		Assert.isTrue(sb.getPlayerTeam(ClanTeams.teamName(c)) == null, "team gone after disband");
		Assert.isTrue(sb.getPlayersTeam("Cl-Lead") == null, "leader without team");
		ctx.detail("prefix, color, friendly fire, members, name change, foreign and orphaned teams ok");
	}

	private static void validation(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		for (String ok : new String[] {"Abc", "a_b_c1", "ABCDEFGHIJKLMNOP"}) Assert.isTrue(ClanService.nameError(ok) == null, "name valid: " + ok);
		for (String bad : new String[] {"ab", "ABCDEFGHIJKLMNOPQ", "ébc", "a b", "a-b", "", null}) Assert.notNull(ClanService.nameError(bad), "name invalid: " + bad);
		for (String ok : new String[] {"AB", "A1b2C", "xx"}) Assert.isTrue(ClanService.tagError(ok) == null, "tag valid: " + ok);
		for (String bad : new String[] {"A", "ABCDEF", "A_B", "É1", "a b", null}) Assert.notNull(ClanService.tagError(bad), "tag invalid: " + bad);
		Assert.isTrue(ClanColor.byId("RED").orElse(null) == ClanColor.RED, "color in uppercase");
		Assert.isTrue(ClanColor.byId("dark_blue").orElse(null) == ClanColor.DARK_BLUE, "English name");
		Assert.isTrue(ClanColor.byId("charcoal").isEmpty() && ClanColor.byId("black").isEmpty(), "unknown color");
		Assert.isTrue(ClanColor.ids().contains("yellow"), "suggestions");
		Assert.isTrue(ClanColor.byId(new ClanConfig().defaultColor).orElse(null) == ClanColor.DEFAULT, "config default color valid");

		FakePlayer poor = player(ctx, "Cl-Poor", fee() - 1);
		FakePlayer rich = player(ctx, "Cl-Rich", fee() * 2);
		UUID P = poor.getUUID(), R = rich.getUUID();
		long t0 = Treasury.balance();
		int clans0 = ClanService.all().size();
		code(Code.INVALID_NAME, ClanService.create(R, "Cl-Rich", "a", freeTag("TV"), null, now), "invalid name");
		code(Code.INVALID_TAG, ClanService.create(R, "Cl-Rich", freeName("TClanVal"), "X", null, now), "invalid tag");
		code(Code.INVALID_COLOR, ClanService.create(R, "Cl-Rich", freeName("TClanVal"), freeTag("TV"), "black", now), "invalid color");
		if (fee() > 0) {
			code(Code.INSUFFICIENT_FUNDS, ClanService.create(P, "Cl-Poor", freeName("TClanVal"), freeTag("TV"), null, now), "not enough money");
			Assert.eq(fee() - 1, bal(poor), "no money charged");
			Assert.isTrue(ClanService.clanOf(P).isEmpty(), "no clan without money");
		}
		Assert.eq(fee() * 2, bal(rich), "failures charge nothing");
		Assert.eq(t0, Treasury.balance(), "treasury unchanged");
		Assert.eq(clans0, ClanService.all().size(), "no clan created");

		Res r = ClanService.create(R, "Cl-Rich", freeName("TVN"), freeTag("TVL"), "gold", now);
		ok(r, "create");
		Clan c = r.clan();
		Assert.eq(fee(), bal(rich), "fee charged");
		Assert.eq(t0 + fee(), Treasury.balance(), "fee in the treasury");
		Assert.eq(fee(), r.amount(), "fee in the result");

		FakePlayer second = player(ctx, "Cl-Second", fee());
		code(Code.NAME_TAKEN, ClanService.create(second.getUUID(), "Cl-Second", c.name.toUpperCase(), freeTag("TW"), null, now), "name taken (case-insensitive)");
		code(Code.TAG_TAKEN, ClanService.create(second.getUUID(), "Cl-Second", freeName("TClanVal"), c.tag.toLowerCase(), null, now), "tag taken (case-insensitive)");
		code(Code.NAME_TAKEN, ClanService.create(second.getUUID(), "Cl-Second", c.tag, freeTag("TW"), null, now), "name = another clan's tag");
		code(Code.TAG_TAKEN, ClanService.create(second.getUUID(), "Cl-Second", freeName("TClanVal"), c.name, null, now), "tag = another clan's name");
		Assert.eq(fee(), bal(second), "taken: nothing charged");
		Assert.isTrue(ClanService.find(c.tag.toLowerCase()).orElse(null) == c, "find by tag");
		Assert.isTrue(ClanService.find(c.name.toLowerCase()).orElse(null) == c, "find by name");
		ctx.detail("names, tags, colors, fee " + Money.format(fee()) + " to the treasury, no charge on errors");
	}

	private static void teleport(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer lead = player(ctx, "Cl-Lead", fee());
		FakePlayer mem = player(ctx, "Cl-Mem", 0);
		FakePlayer out = player(ctx, "Cl-Out", 0);
		UUID L = lead.getUUID(), M = mem.getUUID();
		ctx.cleanup(() -> {
			ClanTeleport.cancel(L);
			ClanTeleport.cancel(M);
			ClanTeleport.cancel(out.getUUID());
		});
		Assert.isFalse(ClanTeleport.moved(new Vec3(0, 64, 0), new Vec3(0.2, 64, 0.1)), "small drift is no movement");
		Assert.isTrue(ClanTeleport.moved(new Vec3(0, 64, 0), new Vec3(0.5, 64, 0)), "0.5 blocks = movement");
		code(Code.NOT_IN_CLAN, ClanTeleport.request(out), "without clan");
		Clan c = create(lead, "TClanTp", "TP", null);
		join(c, lead, mem);
		code(Code.NO_HOME, ClanTeleport.request(lead), "without home");

		Vec3 start = lead.position();
		Home h = ClanTeleport.homeOf(lead);
		h.x += 7;
		h.z -= 3;
		ok(ClanService.setHome(L, h), "set home");
		int ticks = ClanService.config().teleportDelaySeconds * 20;

		// No movement: at home after exactly delay ticks
		ok(ClanTeleport.request(lead), "request teleport");
		ClanTeleport.Pending p = ClanTeleport.pending(L);
		Assert.notNull(p, "waiting");
		for (int i = 1; i < ticks; i++) Assert.eq(ClanTeleport.Step.WAITING, ClanTeleport.step(p), "waiting at tick " + i);
		Assert.eq(ClanTeleport.Step.DONE, ClanTeleport.step(p), "teleported after " + ticks + " ticks");
		Assert.isTrue(lead.position().distanceToSqr(new Vec3(h.x, h.y, h.z)) < 0.01, "at home (" + lead.position() + ")");
		ClanTeleport.cancel(L);

		lead.teleportTo(start.x, start.y, start.z);
		ok(ClanTeleport.request(lead), "Teleport 2");
		ClanTeleport.Pending p2 = ClanTeleport.pending(L);
		Assert.eq(ClanTeleport.Step.WAITING, ClanTeleport.step(p2), "waiting");
		lead.teleportTo(start.x + 1, start.y, start.z);
		Assert.eq(ClanTeleport.Step.CANCELLED, ClanTeleport.step(p2), "movement cancels");
		Assert.isTrue(lead.position().distanceToSqr(new Vec3(h.x, h.y, h.z)) > 1, "not teleported");
		ClanTeleport.cancel(L);

		// Leaving during the wait: no teleport
		Vec3 memStart = mem.position();
		ok(ClanTeleport.request(mem), "teleport member");
		ClanTeleport.Pending p3 = ClanTeleport.pending(M);
		ok(ClanService.leave(M), "leave");
		ClanTeleport.Step last = ClanTeleport.Step.WAITING;
		for (int i = 0; i < ticks && last == ClanTeleport.Step.WAITING; i++) last = ClanTeleport.step(p3);
		Assert.eq(ClanTeleport.Step.FAILED, last, "no teleport after leaving");
		Assert.isTrue(mem.position().distanceToSqr(memStart) < 0.01, "member not moved");
		ctx.detail(ticks + " ticks delay, cancel on movement or leaving ok");
	}

	/**
	 * Open join without invite: /clan home only after openJoinHomeMinutes (checks both request and trigger).
	 */
	private static void homeLock(TestContext ctx) {
		ctx.preserveTreasury();
		ClanConfig cfg = ClanService.config();
		int oldLock = cfg.openJoinHomeMinutes;
		ctx.cleanup(() -> cfg.openJoinHomeMinutes = oldLock);
		cfg.openJoinHomeMinutes = 30;
		long lockMs = 30 * 60_000L;
		FakePlayer lead = player(ctx, "Cl-Lead", fee());
		FakePlayer str = player(ctx, "Cl-Str", 0);
		FakePlayer inv = player(ctx, "Cl-Inv", 0);
		UUID L = lead.getUUID(), S = str.getUUID(), I = inv.getUUID();
		ctx.cleanup(() -> {
			ClanTeleport.cancel(L);
			ClanTeleport.cancel(S);
			ClanTeleport.cancel(I);
		});
		Clan c = create(lead, "TClanLock", "LK", null);
		Home h = ClanTeleport.homeOf(lead);
		h.x += 9;
		ok(ClanService.setHome(L, h), "set home");
		ok(ClanService.toggleOpen(L), "open");
		Assert.isTrue(ClanService.toggleOpen(L).ok() && ClanService.toggleOpen(L).ok() && c.open, "toggle there and back");

		// Stranger joins openly: locked, no pending teleport
		ok(ClanService.join(S, "Cl-Str", c.name, now()), "open join");
		Assert.isTrue(c.member(S).openJoin, "marked as open join");
		code(Code.HOME_LOCKED, ClanTeleport.request(str), "home right after open join");
		Assert.isTrue(ClanTeleport.pending(S) == null, "no pending teleport");
		long left = ClanService.homeLockRemaining(c, S, now());
		Assert.isTrue(left > lockMs - 60_000 && left <= lockMs, "remaining ~30 min (" + left + ")");
		c.member(S).joinedAt = now() + 10 * lockMs; // Clock jump: never longer than the configured lock
		Assert.eq(lockMs, ClanService.homeLockRemaining(c, S, now()), "remaining time capped");

		// Invited (also while open, through accept or join): allowed right away
		ok(ClanService.invite(L, I, "Cl-Inv", now()), "invite");
		ok(ClanService.join(I, "Cl-Inv", c.name, now()), "join an open clan with an invite");
		Assert.isFalse(c.member(I).openJoin, "invited = no open join");
		ok(ClanTeleport.request(inv), "invited player may go right away");
		ClanTeleport.cancel(I);

		c.member(S).joinedAt = now() - lockMs - 1000;
		Assert.eq(0L, ClanService.homeLockRemaining(c, S, now()), "lock expired");
		ok(ClanTeleport.request(str), "allowed after expiry");
		ClanTeleport.cancel(S);

		// Leaving during the wait and rejoining openly: locked again at trigger
		Vec3 sStart = str.position();
		ok(ClanTeleport.request(str), "request teleport");
		ClanTeleport.Pending p = ClanTeleport.pending(S);
		Assert.notNull(p, "waiting");
		ok(ClanService.leave(S), "leave");
		ok(ClanService.join(S, "Cl-Str", c.name, now()), "open join again");
		ClanTeleport.Step last = ClanTeleport.Step.WAITING;
		int ticks = cfg.teleportDelaySeconds * 20;
		for (int i = 0; i <= ticks && last == ClanTeleport.Step.WAITING; i++) last = ClanTeleport.step(p);
		Assert.eq(ClanTeleport.Step.FAILED, last, "rejoin: teleport locked at trigger");
		Assert.isTrue(str.position().distanceToSqr(sStart) < 0.01, "not teleported");
		ClanTeleport.cancel(S);

		// Officer: promotion lifts the lock; 0 min means no lock
		ok(ClanService.promote(L, S, false), "promote to officer");
		Assert.eq(0L, ClanService.homeLockRemaining(c, S, now()), "officer without lock");
		ok(ClanService.demote(L, S), "demote");
		Assert.isTrue(ClanService.homeLockRemaining(c, S, now()) > 0, "member locked again");
		cfg.openJoinHomeMinutes = 0;
		Assert.eq(0L, ClanService.homeLockRemaining(c, S, now()), "0 min = off");
		ok(ClanTeleport.request(str), "allowed without lock");
		ClanTeleport.cancel(S);
		ctx.detail("open join locked 30 min (request + trigger), invite, officer, expiry clear");
	}

	private static void chat(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer lead = player(ctx, "Cl-Lead", fee());
		FakePlayer mem = player(ctx, "Cl-Mem", 0);
		FakePlayer out = player(ctx, "Cl-Out", 0);
		UUID L = lead.getUUID(), M = mem.getUUID();
		Assert.eq("Hello World", ClanChat.clean("§cHello\u0007 World "), "text cleaned");
		Assert.eq(ClanChat.MAX_LENGTH, ClanChat.clean("x".repeat(1000)).length(), "text truncated");
		code(Code.NOT_IN_CLAN, ClanService.toggleChat(out.getUUID()), "chat mode without clan");
		Clan c = create(lead, "TClanChat", "TC", null);
		join(c, lead, mem);
		Assert.eq("[Clan] ★ Cl-Lead: hi ab", ClanChat.format(c, c.member(L), "hi §ka§xb").getString(), "format (without color codes)");
		Assert.eq("[Clan] Cl-Mem: test", ClanChat.format(c, c.member(M), "test").getString(), "format member");
		Assert.eq(-1, ClanChat.send(lead, c, "  "), "empty message");
		Assert.eq(-1, ClanChat.send(out, c, "hello"), "non-member does not send");
		Assert.eq(0, ClanChat.send(lead, c, "hello"), "test players are not online → 0 recipients");

		Res on = ClanService.toggleChat(M);
		ok(on, "chat mode on");
		Assert.eq(1, on.amount(), "on");
		Assert.isTrue(ClanService.chatToggled(M), "mode set");
		Res off = ClanService.toggleChat(M);
		Assert.eq(0, off.amount(), "off");
		ClanService.toggleChat(M);
		ok(ClanService.kick(L, M), "kick");
		Assert.isFalse(ClanService.chatToggled(M), "kick ends chat mode");
		ctx.detail("cleaning, format, recipients, toggle ok");
	}

	private static void persistence(TestContext ctx) {
		UUID leader = UUID.nameUUIDFromBytes("clans-selftest-leader".getBytes());
		UUID member = UUID.nameUUIDFromBytes("clans-selftest-member".getBytes());
		UUID dup = UUID.nameUUIDFromBytes("clans-selftest-dup".getBytes());
		UUID bank1 = UUID.nameUUIDFromBytes("clans-selftest-bank1".getBytes());
		UUID bank2 = UUID.nameUUIDFromBytes("clans-selftest-bank2".getBytes());
		ctx.cleanup(() -> {
			for (UUID b : new UUID[] {bank1, bank2}) {
				EconomyService.deleteAccount(b);
				EconomyService.unregisterSystemAccount(b);
			}
		});
		Data d = new Data();
		Clan c = new Clan();
		c.id = 7;
		c.name = "Persist";
		c.tag = "PST";
		c.color = "purple";
		c.bank = bank1;
		c.open = true;
		c.members.put(leader, new Member("Cl-Lead", Role.LEADER, 123));
		c.members.put(member, new Member("Cl-Mem", Role.OFFICER, 456));
		c.members.get(member).openJoin = true;
		c.invites.put(dup, 999L);
		c.home = new Home();
		c.home.dim = "minecraft:the_nether";
		c.home.x = 1.5;
		c.home.y = 70;
		c.home.z = -3.25;
		LogEntry e = new LogEntry();
		e.type = LogEntry.WITHDRAW;
		e.amount = 4200;
		e.name = "Cl-Lead";
		c.log.add(e);
		d.clans.put(7, c);
		d.nextId = 8;
		d.chatToggled.add(member);

		String json = DataStore.gson().toJson(d);
		Data back = DataStore.gson().fromJson(json, Data.class);
		Clan b = back.clans.get(7);
		Assert.notNull(b, "clan by integer key");
		Assert.eq("Persist", b.name, "Name");
		Assert.eq(Role.OFFICER, b.role(member), "role");
		Assert.eq(Role.LEADER, b.role(leader), "Leader");
		Assert.isTrue(b.member(member).openJoin && !b.member(leader).openJoin, "open join saved");
		Assert.eq(bank1, b.bank, "bank account");
		Assert.eq("minecraft:the_nether", b.home.dim, "home dimension");
		Assert.eq(-3.25, b.home.z, "Home z");
		Assert.eq(4200, b.log.get(0).amount, "Log");
		Assert.eq(999L, (long) b.invites.get(dup), "invite");
		Assert.isTrue(back.chatToggled.contains(member), "chat mode");
		Assert.isTrue(b.open, "open");

		// Repair: no leader, duplicate membership, nulls, nextId too small
		Clan c2 = new Clan();
		c2.id = 3;
		c2.name = null;
		c2.tag = "RP";
		c2.color = "nonexistent";
		c2.bank = bank2;
		c2.members.put(member, new Member("Cl-Mem", Role.MEMBER, 1)); // Duplicate (already in clan 7)
		c2.members.put(dup, new Member(null, null, 2));
		c2.invites = null;
		c2.log = null;
		back.clans.put(3, c2);
		back.clans.get(7).members.get(leader).role = Role.MEMBER; // Clan 7 without leader: officer moves up
		back.nextId = 2;
		back.chatToggled.add(dup);
		ClanService.repair(back, "Selftest");
		Assert.eq(Role.LEADER, back.clans.get(7).role(member), "officer becomes leader");
		Assert.eq(1, back.clans.get(7).count(Role.LEADER), "one leader");
		Assert.isFalse(back.clans.get(3).members.containsKey(member), "double membership removed");
		Assert.eq(Role.LEADER, back.clans.get(3).role(dup), "only member becomes leader");
		Assert.eq("Clan3", back.clans.get(3).name, "name filled in");
		Assert.eq(ClanColor.DEFAULT.id, back.clans.get(3).color, "color repaired");
		Assert.notNull(back.clans.get(3).invites, "invites filled in");
		Assert.notNull(back.clans.get(3).log, "log filled in");
		Assert.eq(8, back.nextId, "nextId > max");
		Assert.isTrue(EconomyService.isSystemAccount(bank1) && EconomyService.isSystemAccount(bank2), "bank accounts registered");
		Assert.isTrue(back.chatToggled.contains(member) && back.chatToggled.contains(dup), "members' chat mode stays");
		ctx.detail("Gson round trip and repair ok");
	}

	/** "Clan-Bank #N" account with money but no clan, as after restoring clans.json from backup. */
	private static UUID orphanAccount(TestContext ctx, String name, long cents) {
		UUID id;
		do {
			id = UUID.randomUUID();
		} while (EconomyService.hasAccount(id));
		UUID fid = id;
		ctx.cleanup(() -> {
			ClanService.forgetOrphanForTests(fid);
			EconomyService.deleteAccount(fid);
			EconomyService.unregisterSystemAccount(fid);
		});
		EconomyService.registerSystemAccount(id, name); // Creates the account with a name...
		EconomyService.unregisterSystemAccount(id);     // ...and forgets the registration, like a restart
		if (cents > 0) EconomyService.set(id, cents);
		return id;
	}

	private static boolean inTop(UUID id) {
		for (EconomyService.Entry e : EconomyService.top()) {
			if (e.id().equals(id)) return true;
		}
		return false;
	}

	private static boolean listed(UUID id) {
		for (ClanService.Orphan o : ClanService.orphans()) {
			if (o.id().equals(id)) return true;
		}
		return false;
	}

	private static void orphans(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer lead = player(ctx, "Cl-Orph", fee() + 50_000);
		FakePlayer rcv = player(ctx, "Cl-Rcv", 1_000);
		Clan c = create(lead, "TClanOrph", "TO", null);
		ok(ClanService.deposit(lead.getUUID(), "Cl-Orph", 20_000, now), "deposit into real clan bank");
		UUID o1 = orphanAccount(ctx, ClanService.BANK_NAME_PREFIX + "990001", 12_345);
		UUID o2 = orphanAccount(ctx, ClanService.BANK_NAME_PREFIX + "990002", 6_789);
		UUID o3 = orphanAccount(ctx, ClanService.BANK_NAME_PREFIX + "990003", 0);
		Assert.isTrue(inTop(o1) && inTop(o2), "precondition: orphaned account is in top()");
		long total0 = EconomyService.totalMoney();

		Assert.isTrue(ClanService.scanOrphanBanks() >= 3, "orphaned accounts found");
		for (UUID o : new UUID[] {o1, o2, o3}) {
			Assert.isTrue(EconomyService.isSystemAccount(o), "registered as system account");
			Assert.isFalse(inTop(o), "no longer in top() or baltop");
			Assert.eq(0, EconomyService.rank(o), "no rank");
			Assert.isTrue(listed(o), "listed in /clan admin orphan");
		}
		Assert.isFalse(EconomyService.knownNames().contains(ClanService.BANK_NAME_PREFIX + "990001"), "not in knownNames()");
		Assert.isFalse(ClanService.isOrphan(c.bank) || listed(c.bank), "a live clan's bank is not orphaned");
		Assert.isTrue(EconomyService.isSystemAccount(c.bank), "clan bank stays a system account");
		Assert.eq(0, ClanService.scanOrphanBanks(), "second scan finds nothing new");
		Assert.eq(12_345, EconomyService.balance(o1), "scan moves no money");

		// A live clan's bank and unknown accounts cannot be paid out
		code(Code.NOT_FOUND, ClanService.resolveOrphan(c.bank, rcv.getUUID()), "pay out clan bank");
		Assert.eq(20_000, ClanService.bankBalance(c), "clan bank unchanged");
		code(Code.NOT_FOUND, ClanService.resolveOrphan(rcv.getUUID(), lead.getUUID()), "player account as orphan");
		code(Code.FAILED, ClanService.resolveOrphan(o1, o2), "recipient is a system account");
		code(Code.FAILED, ClanService.resolveOrphan(o1, Treasury.ID), "recipient treasury as player");
		Assert.eq(12_345, EconomyService.balance(o1), "unchanged after failures");

		long rcv0 = bal(rcv);
		Res r = ClanService.resolveOrphan(o1, rcv.getUUID());
		ok(r, "pay out to player");
		Assert.eq(12_345, r.amount(), "amount");
		Assert.eq(rcv0 + 12_345, bal(rcv), "player gets the balance");
		Assert.isFalse(EconomyService.hasAccount(o1), "account deleted");
		Assert.isFalse(EconomyService.isSystemAccount(o1), "system account unregistered");
		Assert.isFalse(listed(o1), "no longer listed");
		code(Code.NOT_FOUND, ClanService.resolveOrphan(o1, rcv.getUUID()), "pay out twice");
		Assert.eq(rcv0 + 12_345, bal(rcv), "not paid twice");

		long t0 = Treasury.balance();
		Res r2 = ClanService.resolveOrphan(o2, null);
		ok(r2, "to treasury");
		Assert.eq(t0 + 6_789, Treasury.balance(), "treasury gets the balance");
		Assert.isFalse(EconomyService.hasAccount(o2), "account deleted");

		// Empty account: only delete
		Res r3 = ClanService.resolveOrphan(o3, null);
		ok(r3, "empty account");
		Assert.eq(0, r3.amount(), "nothing paid");
		Assert.isFalse(EconomyService.hasAccount(o3), "empty account deleted");

		Assert.eq(total0, EconomyService.totalMoney(), "no money created or lost");
		Assert.eq(20_000, ClanService.bankBalance(c), "real clan bank untouched");
		ctx.detail("registration (no baltop, rank, knownNames), payout to player and treasury, money preserved");
	}

	private static void top(TestContext ctx) {
		ctx.preserveTreasury();
		long now = now();
		FakePlayer l1 = player(ctx, "Cl-Top1", fee() + 300_000);
		FakePlayer l2 = player(ctx, "Cl-Top2", fee() + 500_000);
		Clan c1 = create(l1, "TClanTopA", "TA", null);
		Clan c2 = create(l2, "TClanTopB", "TZ", null);
		ok(ClanService.deposit(l1.getUUID(), "Cl-Top1", 300_000, now), "deposit c1");
		List<ClanService.TopEntry> top = ClanService.top();
		int i1 = -1, i2 = -1;
		for (int i = 0; i < top.size(); i++) {
			if (top.get(i).clan() == c1) i1 = i;
			if (top.get(i).clan() == c2) i2 = i;
		}
		Assert.isTrue(i1 >= 0 && i2 >= 0, "both clans listed");
		Optional<Services.Bank> bankService = Services.find(Services.Bank.class);
		long extra1 = bankService.map(s -> s.balance(l1.getUUID())).orElse(0L);
		long extra2 = bankService.map(s -> s.balance(l2.getUUID())).orElse(0L);
		Assert.eq(300_000 + bal(l1) + extra1, top.get(i1).total(), "c1 = bank + account");
		Assert.eq(300_000, top.get(i1).bank(), "c1 Bank");
		Assert.eq(bal(l2) + extra2, top.get(i2).total(), "c2 = account");
		Assert.isTrue((top.get(i1).total() > top.get(i2).total()) == (i1 < i2), "order by total value");
		for (int i = 1; i < top.size(); i++) Assert.isTrue(top.get(i - 1).total() >= top.get(i).total(), "sorted descending");
		ctx.detail("bank + wealth, descending");
	}
}
