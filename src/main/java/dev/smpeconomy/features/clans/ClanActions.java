package dev.smpeconomy.features.clans;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Member;
import dev.smpeconomy.features.clans.ClanData.Role;
import dev.smpeconomy.features.clans.ClanService.Code;
import dev.smpeconomy.features.clans.ClanService.Res;

/**
 * Player actions for commands and GUIs: calls {@link ClanService}, sends messages and sounds to the actor,
 * and notifies those affected (online right away, important things offline as pending messages). Returns
 * success.
 */
public final class ClanActions {
	/** Confirmations by repeat or chat click (disband, hand over leadership): player to {key, expiry}. */
	private static final Map<UUID, Map.Entry<String, Long>> CONFIRM = new HashMap<>();
	static final long CONFIRM_MS = 30_000;

	private ClanActions() {}

	static long now() {
		return System.currentTimeMillis();
	}

	static String name(ServerPlayer p) {
		return p.getGameProfile().name();
	}

	static boolean report(ServerPlayer p, Res r) {
		if (r.ok()) {
			Msg.success(p, r.message());
			Gui.successSound(p);
		} else {
			Msg.error(p, r.message());
			Gui.failSound(p);
		}
		return r.ok();
	}

	static void notifyClan(Clan c, String text, UUID except) {
		MinecraftServer server = dev.smpeconomy.SmpEconomy.server();
		for (ServerPlayer p : ClanChat.onlineMembers(server, c)) {
			if (!p.getUUID().equals(except)) Msg.info(p, text);
		}
	}

	static void announce(String text) {
		MinecraftServer server = dev.smpeconomy.SmpEconomy.server();
		if (server == null || !ClanService.config().announce) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) Msg.info(p, text);
		}
	}

	static void requestConfirm(UUID player, String key) {
		CONFIRM.put(player, Map.entry(key, Util.getMillis() + CONFIRM_MS));
	}

	/** True, consuming the confirmation, if {@code key} was requested in the last 30 s. */
	static boolean consumeConfirm(UUID player, String key) {
		Map.Entry<String, Long> e = CONFIRM.get(player);
		if (e == null || !e.getKey().equals(key) || e.getValue() < Util.getMillis()) return false;
		CONFIRM.remove(player);
		return true;
	}

	static boolean create(ServerPlayer p, String name, String tag, String color) {
		Res r = ClanService.create(p.getUUID(), name(p), name, tag, color, now());
		report(p, r);
		if (r.ok()) {
			Msg.info(p, "§7Invite players with §e/clan invite <player>§7. Overview: §e/clan");
			announce("§7New clan: " + r.clan().display() + " §7by §f" + name(p) + "§7.");
		}
		return r.ok();
	}

	static boolean invite(ServerPlayer p, ServerPlayer target) {
		Res r = ClanService.invite(p.getUUID(), target.getUUID(), name(target), now());
		report(p, r);
		if (!r.ok()) return false;
		Clan c = r.clan();
		MutableComponent line = Msg.prefixed("§f" + name(p) + " §7invited you to the clan " + c.display() + "§7. ")
			.append(Msg.clickRun("§a[Accept]", "/clan accept " + c.name, "§7Join the clan"))
			.append(Msg.legacy(" "))
			.append(Msg.clickRun("§c[Decline]", "/clan deny " + c.name, "§7Decline the invite"));
		Msg.send(target, line);
		Msg.info(target, "§8Valid for " + ClanService.config().inviteExpiryMinutes + " min · /clan accept or /clan");
		Gui.successSound(target);
		notifyClan(c, "§f" + name(p) + " §7invited §f" + name(target) + "§7.", p.getUUID());
		return true;
	}

	static boolean accept(ServerPlayer p, String clanFilter) {
		return joined(p, ClanService.accept(p.getUUID(), name(p), clanFilter, now()));
	}

	static boolean join(ServerPlayer p, String clan) {
		return joined(p, ClanService.join(p.getUUID(), name(p), clan, now()));
	}

	private static boolean joined(ServerPlayer p, Res r) {
		report(p, r);
		if (r.ok()) notifyClan(r.clan(), "§f" + name(p) + " §ajoined the clan.", p.getUUID());
		return r.ok();
	}

	static boolean deny(ServerPlayer p, String clanFilter) {
		Res r = ClanService.deny(p.getUUID(), clanFilter, now());
		if (r.ok()) {
			Msg.info(p, r.message());
			Gui.click(p);
			if (clanFilter != null || r.amount() == 1) notifyOfficers(r.clan(), "§f" + name(p) + " §7declined the invite.");
		} else {
			report(p, r);
		}
		return r.ok();
	}

	private static void notifyOfficers(Clan c, String text) {
		MinecraftServer server = dev.smpeconomy.SmpEconomy.server();
		for (ServerPlayer o : ClanChat.onlineMembers(server, c)) {
			Role role = c.role(o.getUUID());
			if (role != null && role.atLeast(Role.OFFICER)) Msg.info(o, text);
		}
	}

	static boolean leave(ServerPlayer p) {
		Res r = ClanService.leave(p.getUUID());
		report(p, r);
		if (r.ok()) {
			ClanTeleport.cancel(p.getUUID());
			notifyClan(r.clan(), "§f" + name(p) + " §7left the clan.", p.getUUID());
		}
		return r.ok();
	}

	static boolean kick(ServerPlayer p, String targetName) {
		Optional<UUID> target = resolveMember(p, targetName);
		return target.isPresent() && kick(p, target.get());
	}

	/** Member of the actor's clan by name, for commands. Reports errors itself. */
	private static Optional<UUID> resolveMember(ServerPlayer p, String targetName) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null) {
			report(p, ClanService.fail(Code.NOT_IN_CLAN, "You're not in a clan."));
			return Optional.empty();
		}
		Optional<UUID> target = c.memberByName(targetName);
		if (target.isEmpty()) report(p, ClanService.fail(Code.NOT_MEMBER, "§f" + targetName + " §cisn't in your clan."));
		return target;
	}

	static boolean kick(ServerPlayer p, UUID target) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		Member m = c == null ? null : c.member(target);
		String targetDisplay = m == null ? "?" : m.display();
		Res r = ClanService.kick(p.getUUID(), target);
		report(p, r);
		if (r.ok()) {
			ClanTeleport.cancel(target);
			Msg.sendOrQueue(target, "§cYou were kicked from the clan " + c.display() + " §cby §f" + name(p) + "§c.");
			notifyClan(c, "§f" + targetDisplay + " §7was kicked by §f" + name(p) + "§7.", p.getUUID());
		}
		return r.ok();
	}

	/**
	 * {@code confirm} means "/clan promote <name> confirm", which needs an open confirmation from the last 30
	 * s.
	 */
	static boolean promote(ServerPlayer p, String targetName, boolean confirm) {
		Optional<UUID> target = resolveMember(p, targetName);
		if (target.isEmpty()) return false;
		boolean confirmed = confirm && consumeConfirm(p.getUUID(), "promote:" + target.get());
		if (confirm && !confirmed) return report(p, ClanService.fail(Code.NEEDS_CONFIRM, "Nothing to confirm – use /clan promote " + targetName + " first."));
		return promote(p, target.get(), confirmed);
	}

	/** {@code confirmed}: leadership handover already confirmed (GUI dialog or command). */
	static boolean promote(ServerPlayer p, UUID target, boolean confirmed) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		Res r = ClanService.promote(p.getUUID(), target, confirmed);
		if (r.code() == Code.NEEDS_CONFIRM && c != null) {
			requestConfirm(p.getUUID(), "promote:" + target);
			Msg.send(p, Msg.prefixed("§eHand over leadership? " + r.message() + " ")
				.append(Msg.clickRun("§a§l[Confirm]", "/clan promote " + c.members.get(target).name + " confirm", "§7Really hand over leadership (valid for 30s)")));
			Gui.click(p);
			return false;
		}
		report(p, r);
		if (r.ok()) {
			Member m = c.members.get(target);
			Msg.sendOrQueue(target, "§aYour rank in " + c.display() + " §ais now §f" + m.role.label + "§a.");
			notifyClan(c, "§f" + m.display() + "§7's rank is now §f" + m.role.label + "§7.", p.getUUID());
		}
		return r.ok();
	}

	static boolean demote(ServerPlayer p, String targetName) {
		Optional<UUID> target = resolveMember(p, targetName);
		return target.isPresent() && demote(p, target.get());
	}

	static boolean demote(ServerPlayer p, UUID target) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		Res r = ClanService.demote(p.getUUID(), target);
		report(p, r);
		if (r.ok()) {
			Msg.sendOrQueue(target, "§7Your rank in " + c.display() + " §7is now §f" + Role.MEMBER.label + "§7.");
			notifyClan(c, "§f" + c.members.get(target).display() + "§7's rank is now §f" + Role.MEMBER.label + "§7.", p.getUUID());
		}
		return r.ok();
	}

	/** Already confirmed. The bank goes to the leader. */
	static boolean disband(ServerPlayer p) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		Res r = ClanService.disband(p.getUUID());
		report(p, r);
		if (r.ok() && c != null) afterDissolve(c, name(p), p.getUUID());
		return r.ok();
	}

	static void afterDissolve(Clan c, String by, UUID except) {
		for (UUID id : c.members.keySet()) {
			ClanTeleport.cancel(id);
			if (!id.equals(except)) Msg.sendOrQueue(id, "§cThe clan " + c.display() + " §cwas disbanded by §f" + by + "§c.");
		}
		announce("§7The clan " + c.display() + " §7has been disbanded.");
	}

	static boolean deposit(ServerPlayer p, long amount) {
		Res r = ClanService.deposit(p.getUUID(), name(p), amount, now());
		report(p, r);
		if (r.ok()) notifyClan(r.clan(), "§f" + name(p) + " §7deposited " + Msg.moneyStr(r.amount()) + " into the Clan Bank.", p.getUUID());
		return r.ok();
	}

	static boolean depositAll(ServerPlayer p) {
		long all = EconomyService.balance(p.getUUID());
		if (all <= 0) return report(p, ClanService.fail(Code.INSUFFICIENT_FUNDS, "You have no money to deposit."));
		return deposit(p, all);
	}

	static boolean withdraw(ServerPlayer p, long amount) {
		Res r = ClanService.withdraw(p.getUUID(), name(p), amount, now());
		report(p, r);
		if (r.ok()) notifyClan(r.clan(), "§f" + name(p) + " §7withdrew " + Msg.moneyStr(r.amount()) + " from the Clan Bank.", p.getUUID());
		return r.ok();
	}

	static boolean withdrawAll(ServerPlayer p) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null) return report(p, ClanService.fail(Code.NOT_IN_CLAN, "You're not in a clan."));
		long all = ClanService.bankBalance(c);
		if (all <= 0) {
			Role role = c.role(p.getUUID());
			if (role == null || !role.atLeast(Role.OFFICER)) return report(p, ClanService.fail(Code.NO_PERMISSION, "Only the leader and officers can withdraw."));
			return report(p, ClanService.fail(Code.BANK_INSUFFICIENT, "The Clan Bank is empty."));
		}
		return withdraw(p, all);
	}

	static boolean setHome(ServerPlayer p) {
		Res r = ClanService.setHome(p.getUUID(), ClanTeleport.homeOf(p));
		report(p, r);
		if (r.ok()) notifyClan(r.clan(), "§f" + name(p) + " §7set a new Clan Home.", p.getUUID());
		return r.ok();
	}

	static boolean home(ServerPlayer p) {
		Res r = ClanTeleport.request(p);
		if (r.ok()) {
			Msg.info(p, "§a" + r.message());
			Gui.click(p);
		} else {
			report(p, r);
		}
		return r.ok();
	}

	static boolean toggleChat(ServerPlayer p) {
		Res r = ClanService.toggleChat(p.getUUID());
		if (r.ok()) {
			Msg.info(p, r.message());
			Gui.click(p);
		} else {
			report(p, r);
		}
		return r.ok();
	}

	static boolean chat(ServerPlayer p, String text) {
		Clan c = ClanService.clanOf(p.getUUID()).orElse(null);
		if (c == null) return report(p, ClanService.fail(Code.NOT_IN_CLAN, "You're not in a clan."));
		if (ClanChat.send(p, c, text) < 0) {
			Msg.error(p, "Empty message.");
			return false;
		}
		return true;
	}

	static boolean toggleOpen(ServerPlayer p) {
		Res r = ClanService.toggleOpen(p.getUUID());
		if (r.ok()) {
			Msg.info(p, r.message());
			Gui.click(p);
			notifyClan(r.clan(), r.clan().open ? "§7The clan is now §aopen§7." : "§7The clan is now §cclosed§7.", p.getUUID());
		} else {
			report(p, r);
		}
		return r.ok();
	}

	static boolean color(ServerPlayer p, String color) {
		Res r = ClanService.setColor(p.getUUID(), color);
		report(p, r);
		return r.ok();
	}
}
