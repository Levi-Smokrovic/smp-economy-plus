package dev.smpeconomy.features.clans;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Member;
import dev.smpeconomy.features.clans.ClanData.Role;

/**
 * Display-only chests without real slots, so no items enter the game. Screens: start menu without a clan,
 * clan overview, member management, clan bank, color picker. All buttons use {@link Gui#button} or {@link
 * Gui#confirm} (click filter and debounce); every action goes through {@link ClanActions} and rechecks the
 * current state.
 */
public final class ClanGui {
	private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH); // Zone applied only at format time: the core.json timezone can change on reload
	static final long[] BANK_STEPS = {10_000, 100_000, 1_000_000};

	private ClanGui() {}

	private static Clan clanOf(ServerPlayer p) {
		return ClanService.clanOf(p.getUUID()).orElse(null);
	}

	public static void open(ServerPlayer p) {
		if (!Gui.canOpen(p)) return;
		Clan c = clanOf(p);
		if (c == null) openNoClan(p);
		else openClan(p, c);
	}

	static void openNoClan(ServerPlayer p) {
		SimpleGui gui = Gui.chest(p, 3, "§8Clans");
		Gui.frame(gui);
		long fee = ClanService.config().creationFee;
		int invites = ClanService.invitesFor(p.getUUID(), ClanActions.now()).size();
		gui.setSlot(4, Gui.item(Items.WHITE_BANNER, "§6§lClans", "§7You're not in a clan.", "", "§7Create your own clan", "§7or accept an invite."));
		gui.setSlot(10, Gui.button(Items.WRITABLE_BOOK, "§a§lCreate Clan", () -> startCreate(p),
			"§7Cost: " + Msg.moneyStr(fee) + " §8(Treasury)", "§7Name: " + ClanService.NAME_MIN + "–" + ClanService.NAME_MAX + " characters",
			"§7Tag: " + ClanService.TAG_MIN + "–" + ClanService.TAG_MAX + " characters", "", "§eClick to create"));
		gui.setSlot(12, invites > 0
			? Gui.button(Items.PAPER, "§e§lInvites §7(" + invites + ")", () -> openInvites(p), "§7View your pending invites", "", "§eClick").glow()
			: Gui.item(Items.PAPER, "§7Invites", "§8No pending invites"));
		gui.setSlot(14, Gui.button(Items.OAK_DOOR, "§b§lOpen Clans", () -> openOpenClans(p), "§7Clans anyone can join", "", "§eClick"));
		gui.setSlot(16, Gui.button(Items.GOLD_INGOT, "§6§lLeaderboard", () -> openTop(p), "§7Clans by bank + wealth", "", "§eClick"));
		Gui.menuButton(gui, 18);
		Gui.fillEmpty(gui);
		gui.open();
	}

	static void openInvites(ServerPlayer p) {
		List<Clan> invites = ClanService.invitesFor(p.getUUID(), ClanActions.now());
		if (invites.isEmpty()) {
			Msg.error(p, "You don't have any pending invites.");
			open(p);
			return;
		}
		SimpleGui gui = Gui.chest(p, 4, "§8Clan Invites");
		Gui.frame(gui);
		int[] slots = Gui.innerSlots(4);
		for (int i = 0; i < slots.length && i < invites.size(); i++) {
			Clan c = invites.get(i);
			String name = c.name;
			gui.setSlot(slots[i], Gui.button(c.colorEnum().banner, c.display(), type -> {
				if (type.isRight) {
					ClanActions.deny(p, name);
					if (Gui.canOpen(p)) {
						if (ClanService.invitesFor(p.getUUID(), ClanActions.now()).isEmpty()) open(p); // No error message after success
						else openInvites(p);
					}
				} else if (ClanActions.accept(p, name) && Gui.canOpen(p)) {
					open(p);
				}
			}, "§7Leader: §f" + c.leaderName(), "§7Members: §f" + c.members.size(), "", "§aLeft-click: accept", "§cRight-click: decline"));
		}
		if (invites.size() > slots.length) {
			gui.setSlot(31, Gui.item(Items.PAPER, "§7+" + (invites.size() - slots.length) + " more invites", "§7Accept with §f/clan accept <clan>"));
		}
		gui.setSlot(27, Gui.backButton(() -> open(p)));
		Gui.fillEmpty(gui);
		gui.open();
	}

	static void openOpenClans(ServerPlayer p) {
		SimpleGui gui = Gui.chest(p, 6, "§8Open Clans");
		Gui.Pager<Clan> pager = new Gui.Pager<>(gui, ClanService::openClans, c -> {
			String name = c.name;
			int lock = ClanService.config().openJoinHomeMinutes;
			String homeNote = lock > 0 && !ClanService.isInvited(c, p.getUUID(), System.currentTimeMillis())
				? "§7Clan Home unlocks after §f" + lock + " min" : "§7Clan Home available right away";
			return Gui.button(c.colorEnum().banner, c.display(), () -> Gui.confirm(p, "§8Join clan?",
				Gui.item(c.colorEnum().banner, c.display(), "§7Leader: §f" + c.leaderName(), "§7Members: §f" + c.members.size(), homeNote),
				() -> {
					if (ClanActions.join(p, name) && Gui.canOpen(p)) open(p);
				},
				() -> openOpenClans(p)),
				"§7Leader: §f" + c.leaderName(), "§7Members: §f" + c.members.size() + "/" + ClanService.config().maxMembers, "", "§eClick to join");
		});
		gui.setSlot(45, Gui.backButton(() -> open(p)));
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(0);
		gui.open();
	}

	static void openTop(ServerPlayer p) {
		SimpleGui gui = Gui.chest(p, 6, "§8Clan Leaderboard");
		List<ClanService.TopEntry> top = ClanService.top();
		List<Integer> ranks = new ArrayList<>();
		for (int i = 0; i < top.size(); i++) ranks.add(i);
		Gui.Pager<Integer> pager = new Gui.Pager<>(gui, () -> ranks, i -> {
			ClanService.TopEntry e = top.get(i);
			return Gui.item(e.clan().colorEnum().banner, "§e#" + (i + 1) + " " + e.clan().display(),
				"§7Total: " + Msg.moneyStr(e.total()), "§7Clan Bank: " + Msg.moneyStr(e.bank()), "§7Members: §f" + e.clan().members.size(),
				"§7Leader: §f" + e.clan().leaderName());
		});
		gui.setSlot(45, Gui.backButton(() -> open(p)));
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(0);
		gui.open();
	}

	static void startCreate(ServerPlayer p) {
		if (clanOf(p) != null) {
			Msg.error(p, "You're already in a clan.");
			return;
		}
		Gui.textInput(p, "§8Clan name (" + ClanService.NAME_MIN + "–" + ClanService.NAME_MAX + ")", "", name -> {
			String err = ClanService.nameError(name);
			if (err == null && ClanService.nameTaken(name)) err = "The name §f" + name + " §cis already taken.";
			if (err != null) {
				Msg.error(p, err);
				Gui.failSound(p);
				return;
			}
			String letters = name.replaceAll("[^A-Za-z0-9]", "");
			String suggested = letters.substring(0, Math.min(3, letters.length())).toUpperCase(Locale.ROOT);
			Gui.textInput(p, "§8Clan tag (" + ClanService.TAG_MIN + "–" + ClanService.TAG_MAX + ")", suggested, tag -> {
				String tagErr = ClanService.tagError(tag);
				if (tagErr == null && ClanService.tagTaken(tag)) tagErr = "The tag §f" + tag + " §cis already taken.";
				if (tagErr != null) {
					Msg.error(p, tagErr);
					Gui.failSound(p);
					return;
				}
				openColorPicker(p, name, tag, null, color -> confirmCreate(p, name, tag, color), () -> open(p));
			}, () -> open(p));
		}, () -> open(p));
	}

	private static void confirmCreate(ServerPlayer p, String name, String tag, ClanColor color) {
		long fee = ClanService.config().creationFee;
		GuiElementBuilder preview = Gui.item(color.banner, "§7[" + color.code + tag + "§7] §f" + name,
			"§7Cost: " + Msg.moneyStr(fee) + " §8(goes to the Treasury)", "§7Your balance: " + Msg.moneyStr(EconomyService.balance(p.getUUID())), "",
			"§aConfirm = create the clan");
		Gui.confirm(p, "§8Create clan?", preview, () -> {
			if (ClanActions.create(p, name, tag, color.id) && Gui.canOpen(p)) open(p);
		}, () -> open(p));
	}

	/** 14 banners in a frame; {@code current} glows. */
	static void openColorPicker(ServerPlayer p, String name, String tag, ClanColor current, Consumer<ClanColor> onPick, Runnable onBack) {
		SimpleGui gui = Gui.chest(p, 4, "§8Choose a Tag Color");
		Gui.frame(gui);
		int[] slots = Gui.innerSlots(4);
		ClanColor[] colors = ClanColor.values();
		for (int i = 0; i < slots.length && i < colors.length; i++) {
			ClanColor color = colors[i];
			GuiElementBuilder b = Gui.button(color.banner, color.code + color.label, () -> onPick.accept(color),
				"§7Preview: §7[" + color.code + tag + "§7] §f" + name, "", "§eClick to choose");
			if (color == current) b.glow();
			gui.setSlot(slots[i], b);
		}
		gui.setSlot(27, Gui.backButton(onBack));
		Gui.fillEmpty(gui);
		gui.open();
	}

	static void openClan(ServerPlayer p, Clan c) {
		SimpleGui gui = Gui.chest(p, 6, "§8Clan " + c.coloredTag() + " §8" + c.name);
		if (renderClan(gui, p, c.id)) gui.open();
	}

	/** Redraws after toggles. False, with the window closed, if the player has left the clan. */
	private static boolean renderClan(SimpleGui gui, ServerPlayer p, int clanId) {
		Clan c = ClanService.byId(clanId).orElse(null);
		if (c == null || !c.members.containsKey(p.getUUID())) {
			if (gui.isOpen()) gui.close();
			return false;
		}
		UUID me = p.getUUID();
		Role role = c.role(me);
		for (int i = 0; i < 54; i++) gui.clearSlot(i);
		for (int i = 0; i < 9; i++) gui.setSlot(i, Gui.border());

		List<String> info = new ArrayList<>();
		info.add("§7Leader: §f" + c.leaderName());
		info.add("§7Members: §f" + c.members.size() + "/" + ClanService.config().maxMembers);
		info.add("§7Clan Bank: " + Msg.moneyStr(ClanService.bankBalance(c)));
		info.add("§7Home: " + (c.home == null ? "§cnot set" : "§f" + c.home.shortString()));
		info.add("§7Status: " + (c.open ? "§aopen" : "§cinvite only"));
		info.add("§7Founded: §f" + DATE.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(c.createdAt)));
		info.add("");
		info.add("§7Your rank: §f" + role.label);
		if (role == Role.LEADER) {
			info.add("");
			info.add("§eClick: change tag color");
			gui.setSlot(4, Gui.button(c.colorEnum().banner, c.display(), () -> openColorPicker(p, c.name, c.tag, c.colorEnum(),
				color -> {
					ClanActions.color(p, color.id);
					reopen(p);
				}, () -> reopen(p)), info.toArray(String[]::new)));
		} else {
			gui.setSlot(4, Gui.item(c.colorEnum().banner, c.display(), info));
		}

		// Members (rows 1 to 3)
		List<Map.Entry<UUID, Member>> members = ClanService.sortedMembers(c);
		for (int i = 0; i < members.size() && i < 27; i++) {
			UUID id = members.get(i).getKey();
			Member m = members.get(i).getValue();
			boolean online = p.level().getServer().getPlayerList().getPlayer(id) != null;
			List<String> lore = new ArrayList<>();
			lore.add("§7Rank: §f" + m.role.label);
			lore.add(online ? "§aOnline" : "§7Offline");
			lore.add("§7Member since: §f" + DATE.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(m.joinedAt)));
			boolean manage = !id.equals(me) && canManage(role, m.role);
			GuiElementBuilder head;
			if (manage) {
				lore.add("");
				lore.add("§eClick: manage");
				head = Gui.button(Items.PLAYER_HEAD, m.role.symbol + "§f" + m.display(), () -> openMember(p, clanId, id), lore.toArray(String[]::new));
			} else {
				head = Gui.item(Items.PLAYER_HEAD, m.role.symbol + "§f" + m.display(), lore);
			}
			if (PLAYER_NAME.matcher(m.name).matches()) head.setProfile(m.name);
			gui.setSlot(9 + i, head);
		}
		for (int i = 9 + Math.min(members.size(), 27); i < 36; i++) gui.setSlot(i, Gui.filler());
		for (int i = 36; i < 45; i++) gui.setSlot(i, Gui.filler());
		Gui.menuButton(gui, 36); // The last row is full of actions, so the main menu goes above it on the left

		// Actions (row 5)
		boolean officer = role.atLeast(Role.OFFICER);
		gui.setSlot(45, Gui.button(Items.GOLD_INGOT, "§6§lClan Bank", () -> openBank(p),
			"§7Balance: " + Msg.moneyStr(ClanService.bankBalance(c)), "§7Deposit: everyone", "§7Withdraw: leader/officers", "", "§eClick"));
		long homeLock = ClanService.homeLockRemaining(c, me, System.currentTimeMillis());
		if (c.home == null) {
			gui.setSlot(46, Gui.item(Items.RED_BED, "§7Clan Home", "§cNot set yet.", officer ? "§7Set it with the compass next to this" : "§7The leader/officers can set it."));
		} else if (homeLock > 0) {
			gui.setSlot(46, Gui.item(Items.RED_BED, "§7Clan Home", "§7" + c.home.shortString(), "§cLocked: " + TimeUtil.formatDuration(Math.max(homeLock, 1000)) + " left",
				"§7Joined without an invite:", "§7Home unlocks after §f" + ClanService.config().openJoinHomeMinutes + " min§7."));
		} else {
			gui.setSlot(46, Gui.button(Items.RED_BED, "§b§lClan Home", () -> {
				gui.close();
				ClanActions.home(p);
			}, "§7" + c.home.shortString(), "§7Teleports after " + ClanService.config().teleportDelaySeconds + "s", "§7(don't move)", "", "§eClick"));
		}
		gui.setSlot(47, officer
			? Gui.button(Items.COMPASS, "§e§lSet Home Here", () -> {
				ClanActions.setHome(p);
				renderClan(gui, p, clanId);
			}, "§7Sets the Clan Home to", "§7your current position.", "", "§eClick")
			: Gui.item(Items.COMPASS, "§7Set Home", "§8Leader/officers only"));
		boolean chatOn = ClanService.chatToggled(me);
		gui.setSlot(48, Gui.button(Items.OAK_SIGN, "§b§lClan Chat: " + (chatOn ? "§aon" : "§coff"), () -> {
			ClanActions.toggleChat(p);
			renderClan(gui, p, clanId);
		}, "§7On: your chat messages only", "§7go to your clan.", "§7Single message: §e/cc <text>", "", "§eClick to toggle"));
		gui.setSlot(49, officer
			? Gui.button(Items.NAME_TAG, "§a§lInvite Player", () -> Gui.textInput(p, "§8Invite player", "", target -> {
				ServerPlayer t = p.level().getServer().getPlayerList().getPlayerByName(target);
				if (t == null) {
					Msg.error(p, "Player §f" + target + " §cisn't online.");
					Gui.failSound(p);
				} else {
					ClanActions.invite(p, t);
				}
				reopen(p);
			}, () -> reopen(p)), "§7Enter a name, the invite is valid", "§7for " + ClanService.config().inviteExpiryMinutes + " minutes.", "", "§eClick")
			: Gui.item(Items.NAME_TAG, "§7Invite Player", "§8Leader/officers only"));
		gui.setSlot(50, Gui.button(Items.GOLD_BLOCK, "§6§lLeaderboard", () -> openTop(p), "§7Clans by bank + wealth", "", "§eClick"));
		if (role == Role.LEADER) {
			gui.setSlot(51, Gui.button(c.open ? Items.LIME_DYE : Items.GRAY_DYE, "§f§lStatus: " + (c.open ? "§aopen" : "§cclosed"), () -> {
				ClanActions.toggleOpen(p);
				renderClan(gui, p, clanId);
			}, openLore()));
			gui.setSlot(52, Gui.button(Items.TNT, "§c§lDisband Clan", () -> Gui.confirm(p, "§8Disband clan?",
				Gui.item(Items.TNT, "§cDisband " + c.display(), "§7The Clan Bank (" + Msg.moneyStr(ClanService.bankBalance(c)) + "§7)",
					"§7goes to you.", "§cThis can't be undone!"),
				() -> ClanActions.disband(p),
				() -> reopen(p)), "§7The bank goes to you,", "§7all members are removed.", "", "§eClick"));
		} else {
			gui.setSlot(51, Gui.item(c.open ? Items.LIME_DYE : Items.GRAY_DYE, "§7Status: " + (c.open ? "§aopen" : "§cclosed")));
			gui.setSlot(52, Gui.button(Items.OAK_DOOR, "§c§lLeave Clan", () -> Gui.confirm(p, "§8Leave clan?",
				Gui.item(Items.OAK_DOOR, "§cLeave " + c.display()),
				() -> ClanActions.leave(p),
				() -> reopen(p)), "", "§eClick"));
		}
		gui.setSlot(53, Gui.closeButton(gui));
		return true;
	}

	/** What "open" means for home and friendly fire. */
	private static String[] openLore() {
		int lock = ClanService.config().openJoinHomeMinutes;
		return new String[] {"§7Open: anyone without a clan", "§7can join with /clan join.",
			lock > 0 ? "§7New members: §f/clan home §7after §f" + lock + " min" : "§eNew members: §f/clan home §eright away",
			"§7Friendly fire against them is off", "§7right away (unwanted: /clan kick).", "", "§eClick to toggle"};
	}

	private static void reopen(ServerPlayer p) {
		if (Gui.canOpen(p)) open(p);
	}

	static boolean canManage(Role actor, Role target) {
		if (actor == Role.LEADER) return target != Role.LEADER;
		return actor == Role.OFFICER && target == Role.MEMBER;
	}

	static void openMember(ServerPlayer p, int clanId, UUID target) {
		Clan c = ClanService.byId(clanId).orElse(null);
		if (c == null || !c.members.containsKey(p.getUUID())) {
			open(p);
			return;
		}
		Member m = c.member(target);
		Role role = c.role(p.getUUID());
		if (m == null || !canManage(role, m.role)) {
			openClan(p, c);
			return;
		}
		String name = m.display();
		SimpleGui gui = Gui.chest(p, 3, "§8Member: " + name);
		GuiElementBuilder head = Gui.item(Items.PLAYER_HEAD, m.role.symbol + "§f" + name, "§7Rank: §f" + m.role.label);
		if (PLAYER_NAME.matcher(name).matches()) head.setProfile(name);
		gui.setSlot(4, head);
		if (role == Role.LEADER && m.role == Role.MEMBER) {
			gui.setSlot(11, Gui.button(Items.LIME_DYE, "§a§lPromote to Officer", () -> {
				ClanActions.promote(p, target, false);
				openMember(p, clanId, target);
			}, "§7Officers can invite, kick members,", "§7withdraw and set the Home.", "", "§eClick"));
		}
		if (role == Role.LEADER && m.role == Role.OFFICER) {
			gui.setSlot(11, Gui.button(Items.GOLDEN_HELMET, "§6§lHand Over Leadership", () -> Gui.confirm(p, "§8Hand over leadership?",
				Gui.item(Items.GOLDEN_HELMET, "§f" + name + " §6becomes Leader", "§7You become an Officer."),
				() -> {
					if (ClanActions.promote(p, target, true)) reopen(p);
				},
				() -> openMember(p, clanId, target)), "§f" + name + " §7becomes Leader,", "§7you become an Officer.", "", "§eClick"));
			gui.setSlot(13, Gui.button(Items.ORANGE_DYE, "§e§lDemote to Member", () -> {
				ClanActions.demote(p, target);
				openMember(p, clanId, target);
			}, "", "§eClick"));
		}
		gui.setSlot(15, Gui.button(Items.IRON_SWORD, "§c§lKick from Clan", () -> Gui.confirm(p, "§8Kick " + name + "?",
			Gui.item(Items.IRON_SWORD, "§cKick " + name),
			() -> {
				ClanActions.kick(p, target);
				reopen(p);
			},
			() -> openMember(p, clanId, target)), "", "§eClick"));
		gui.setSlot(18, Gui.backButton(() -> reopen(p)));
		Gui.fillEmpty(gui);
		gui.open();
	}

	static void openBank(ServerPlayer p) {
		Clan c = clanOf(p);
		if (c == null) {
			Msg.error(p, "You're not in a clan.");
			Gui.failSound(p);
			return;
		}
		SimpleGui gui = Gui.chest(p, 5, "§8Clan Bank " + c.coloredTag());
		if (renderBank(gui, p, c.id)) gui.open();
	}

	private static boolean renderBank(SimpleGui gui, ServerPlayer p, int clanId) {
		Clan c = ClanService.byId(clanId).orElse(null);
		if (c == null || !c.members.containsKey(p.getUUID())) {
			if (gui.isOpen()) gui.close();
			return false;
		}
		for (int i = 0; i < 45; i++) gui.clearSlot(i);
		Gui.frame(gui, false);
		boolean officer = c.role(p.getUUID()).atLeast(Role.OFFICER);
		long bank = ClanService.bankBalance(c);
		long wallet = EconomyService.balance(p.getUUID());
		gui.setSlot(4, Gui.item(Items.GOLD_BLOCK, "§6§lClan Bank " + c.coloredTag(), "§7Balance: " + Msg.moneyStr(bank), "§7Your balance: " + Msg.moneyStr(wallet),
			"", "§8Deposit: everyone · Withdraw: leader/officers"));
		Runnable refresh = () -> renderBank(gui, p, clanId);
		Runnable back = () -> openBank(p);
		// Plain members cannot withdraw: every deposit needs confirmation, warning already in the tooltip
		String note = officer ? "§8Withdraw again: leader/officers" : WITHDRAW_WARNING;
		String click = officer ? "§eClick" : "§eClick §7(asks to confirm)";
		for (int i = 0; i < BANK_STEPS.length; i++) {
			long amount = BANK_STEPS[i];
			gui.setSlot(10 + i, Gui.button(Items.LIME_DYE, "§aDeposit +" + Money.formatShort(amount), () -> {
				if (isOfficer(p)) { // Role at click time, not draw time: a demotion can happen while the GUI is open
					ClanActions.deposit(p, amount);
					refresh.run();
				} else if (!confirmDeposit(p, amount, false, back)) {
					refresh.run();
				}
			}, "§7Your balance: " + Msg.moneyStr(wallet), "", note, "", click));
		}
		// "Deposit all" sits next to the step buttons, so always confirm, even for leader and officers
		gui.setSlot(13, Gui.button(Items.EMERALD, "§a§lDeposit All", () -> {
			if (!confirmDeposit(p, EconomyService.balance(p.getUUID()), true, back)) refresh.run();
		}, "§7Amount: " + Msg.moneyStr(wallet), "", note, "", "§eClick §7(asks to confirm)"));
		gui.setSlot(14, Gui.button(Items.NAME_TAG, "§aDeposit Amount…", () -> amountInput(p, true), "§7e.g. 2500, 2.5k, all", "", note, "", click));

		List<String> log = new ArrayList<>();
		if (c.log.isEmpty()) log.add("§8No transactions yet.");
		for (int i = c.log.size() - 1; i >= 0; i--) log.add(ClanCommands.logLine(c.log.get(i)));
		gui.setSlot(16, Gui.item(Items.BOOK, "§e§lStatement §7(last " + ClanService.LOG_SIZE + ")", log));

		if (officer) {
			for (int i = 0; i < BANK_STEPS.length; i++) {
				long amount = BANK_STEPS[i];
				gui.setSlot(28 + i, Gui.button(Items.RED_DYE, "§cWithdraw -" + Money.formatShort(amount), () -> {
					ClanActions.withdraw(p, amount);
					refresh.run();
				}, "§7Clan Bank: " + Msg.moneyStr(bank), "", "§eClick"));
			}
			gui.setSlot(31, Gui.button(Items.REDSTONE, "§c§lWithdraw All", () -> {
				ClanActions.withdrawAll(p);
				refresh.run();
			}, "§7Amount: " + Msg.moneyStr(bank), "", "§eClick"));
			gui.setSlot(32, Gui.button(Items.NAME_TAG, "§cWithdraw Amount…", () -> amountInput(p, false), "§7e.g. 2500, 2.5k, all", "", "§eClick"));
		} else {
			for (int slot = 28; slot <= 32; slot++) gui.setSlot(slot, Gui.item(Items.GRAY_DYE, "§7Withdraw", "§8Leader and officers only"));
		}
		gui.setSlot(36, Gui.backButton(() -> reopen(p)));
		gui.setSlot(44, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		return true;
	}

	private static void amountInput(ServerPlayer p, boolean deposit) {
		Gui.textInput(p, deposit ? "§8Deposit amount" : "§8Withdraw amount", "", text -> {
			String in = text.trim();
			boolean all = in.equalsIgnoreCase("all") || in.equalsIgnoreCase("everything");
			OptionalLong amount = all ? OptionalLong.empty() : Money.parse(in);
			if (!all && amount.isEmpty()) {
				Msg.error(p, "Invalid amount. Examples: §e100§c, §e2.5k§c, §eall");
				Gui.failSound(p);
			} else if (deposit) {
				// Members: confirmation dialog that reopens the bank itself
				if (depositOrConfirm(p, all ? EconomyService.balance(p.getUUID()) : amount.getAsLong(), all, () -> openBank(p)) == DepositStep.DIALOG) return;
			} else if (all) {
				ClanActions.withdrawAll(p);
			} else {
				ClanActions.withdraw(p, amount.getAsLong());
			}
			if (Gui.canOpen(p)) openBank(p);
		}, () -> {
			if (Gui.canOpen(p)) openBank(p);
		});
	}

	static final String WITHDRAW_WARNING = "§cOnly the leader/officers can withdraw it again.";

	static boolean isOfficer(ServerPlayer p) {
		Clan c = clanOf(p);
		Role r = c == null ? null : c.role(p.getUUID());
		return r != null && r.atLeast(Role.OFFICER);
	}

	enum DepositStep { BOOKED, FAILED, DIALOG }

	/**
	 * Typed deposit (text input, {@code /clan bank deposit}): leader and officers book right away, plain
	 * members get {@link #confirmDeposit}. With {@code all}, {@code amount} is the balance at call time.
	 */
	static DepositStep depositOrConfirm(ServerPlayer p, long amount, boolean all, Runnable back) {
		if (isOfficer(p) || clanOf(p) == null) { // No clan: ClanActions reports "You're not in a clan."
			boolean ok = all ? ClanActions.depositAll(p) : ClanActions.deposit(p, amount);
			return ok ? DepositStep.BOOKED : DepositStep.FAILED;
		}
		return confirmDeposit(p, amount, all, back) ? DepositStep.DIALOG : DepositStep.FAILED;
	}

	/**
	 * Dialog before a deposit: amount, account and clan bank before and after, and for plain members the
	 * warning that only leader and officers can withdraw. Confirming books exactly the amount shown (with
	 * "all", money arriving later is not included) and only into the clan shown; {@link ClanService#deposit}
	 * rechecks funds, permissions and bank limit. {@code back} (null from the command) reopens the bank
	 * afterwards or on cancel.
	 *
	 * @return whether the dialog opened; otherwise an error went to chat and nothing was booked
	 */
	static boolean confirmDeposit(ServerPlayer p, long amount, boolean all, Runnable back) {
		Clan c = clanOf(p);
		if (c == null) {
			Msg.error(p, "You're not in a clan.");
			Gui.failSound(p);
			return false;
		}
		long wallet = EconomyService.balance(p.getUUID());
		String error = null;
		if (all && amount <= 0) error = "You have no money to deposit.";
		else if (!Money.isValidAmount(amount)) error = "Invalid amount.";
		else if (wallet < amount) error = "Not enough money (you have " + Msg.moneyStr(wallet) + "§c).";
		if (error != null) {
			Msg.error(p, error);
			Gui.failSound(p);
			return false;
		}
		if (!Gui.canOpen(p)) return false;
		int clanId = c.id;
		String clanName = c.display();
		boolean officer = c.role(p.getUUID()).atLeast(Role.OFFICER);
		long bank = ClanService.bankBalance(c);
		List<String> lore = new ArrayList<>(List.of(
			"§7Amount: " + Msg.moneyStr(amount),
			"§7Your balance: " + Msg.moneyStr(wallet) + " §8→ " + Msg.moneyStr(wallet - amount),
			"§7Clan Bank: " + Msg.moneyStr(bank) + " §8→ " + Msg.moneyStr(bank + amount),
			""));
		if (officer) {
			lore.add("§8As leader/officer you can withdraw it again.");
		} else {
			lore.add(WITHDRAW_WARNING);
			lore.add("§8If you leave, the money stays in the clan;");
			lore.add("§8if the clan is disbanded, it goes to the leader.");
		}
		GuiElementBuilder preview = Gui.item(Items.EMERALD, "§aDeposit " + Money.format(amount) + " §7→ " + c.coloredTag(), lore);
		Gui.confirm(p, "§8Confirm Deposit", preview, () -> {
			Clan now = clanOf(p);
			if (now == null || now.id != clanId) {
				Msg.error(p, "You're no longer in " + clanName + "§c – nothing deposited.");
				Gui.failSound(p);
			} else {
				ClanActions.deposit(p, amount);
			}
			if (back != null && Gui.canOpen(p) && clanOf(p) != null) back.run();
		}, back != null ? back : () -> Msg.info(p, "Cancelled – nothing deposited."));
		return true;
	}
}
