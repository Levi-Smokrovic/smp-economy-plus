package dev.smpeconomy.features.clans;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Data;
import dev.smpeconomy.features.clans.ClanData.Home;
import dev.smpeconomy.features.clans.ClanData.LogEntry;
import dev.smpeconomy.features.clans.ClanData.Member;
import dev.smpeconomy.features.clans.ClanData.Role;

/**
 * Clan logic without player objects (UUID-based, testable). Every method validates on its own and returns a
 * {@link Res} with an English player message; messages to others, sounds and GUIs live in {@link
 * ClanActions}. Scoreboard teams are synced through {@link ClanTeams} after each change.
 *
 * <p>The clan bank is a system account in {@link EconomyService} with a random UUID per clan. Deposits and
 * withdrawals are atomic {@code EconomyService.transfer}s within economy.json, so no money appears or
 * vanishes. The creation fee goes to the treasury through {@link Treasury#collectFrom}. Disbanding transfers
 * the whole bank to the leader and deletes the then empty account.
 */
public final class ClanService {
	public static final int LOG_SIZE = 20;
	public static final int MAX_INVITES = 50;
	public static final String TREASURY_REASON = "clans_fee";
	public static final String TREASURY_REASON_ORPHAN = "clans_orphan";
	public static final String BANK_NAME_PREFIX = "Clan-Bank #";
	public static final int NAME_MIN = 3;
	public static final int NAME_MAX = 16;
	public static final int TAG_MIN = 2;
	public static final int TAG_MAX = 5;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");
	private static final Pattern TAG = Pattern.compile("[A-Za-z0-9]+");

	static final ConfigManager.Config<ClanConfig> CONFIG = ConfigManager.register("clans", ClanConfig.class, ClanConfig::new, ClanConfig::sanitize);
	static final DataStore<Data> STORE = new DataStore<>("clans.json", Data.class, Data::new);

	static {
		STORE.onLoad(d -> repair(d, "clans.json"));
	}

	private ClanService() {}

	/** Forces class loading (config, DataStore). Called from ClansFeature.register(). */
	static void bootstrap() {
	}

	public static ClanConfig config() {
		return CONFIG.get();
	}

	public enum Code {
		OK, NOT_IN_CLAN, ALREADY_IN_CLAN, NO_PERMISSION, INVALID_NAME, INVALID_TAG, INVALID_COLOR, NAME_TAKEN, TAG_TAKEN,
		INSUFFICIENT_FUNDS, NOT_FOUND, NOT_INVITED, AMBIGUOUS, CLAN_FULL, NOT_MEMBER, SELF, LEADER_CANNOT_LEAVE, ALREADY_ROLE,
		NEEDS_CONFIRM, INVALID_AMOUNT, BANK_INSUFFICIENT, LIMIT_EXCEEDED, NO_HOME, HOME_LOCKED, TOO_MANY_INVITES, FAILED
	}

	/** Code, player message (§ text without prefix), affected clan or null, amount for money actions. */
	public record Res(Code code, String message, Clan clan, long amount) {
		public boolean ok() {
			return code == Code.OK;
		}
	}

	static Res ok(Clan clan, String message) {
		return new Res(Code.OK, message, clan, 0);
	}

	static Res ok(Clan clan, String message, long amount) {
		return new Res(Code.OK, message, clan, amount);
	}

	static Res fail(Code code, String message) {
		return new Res(code, message, null, 0);
	}

	static Res fail(Code code, String message, Clan clan) {
		return new Res(code, message, clan, 0);
	}

	public static Data data() {
		return STORE.get();
	}

	public static Collection<Clan> all() {
		return Collections.unmodifiableCollection(data().clans.values());
	}

	public static Optional<Clan> byId(int id) {
		return Optional.ofNullable(data().clans.get(id));
	}

	public static Optional<Clan> clanOf(UUID player) {
		return clanOf(data(), player);
	}

	private static Optional<Clan> clanOf(Data d, UUID player) {
		if (player == null) return Optional.empty();
		for (Clan c : d.clans.values()) {
			if (c.members.containsKey(player)) return Optional.of(c);
		}
		return Optional.empty();
	}

	public static Optional<Clan> byName(String name) {
		if (name == null) return Optional.empty();
		for (Clan c : data().clans.values()) {
			if (c.name.equalsIgnoreCase(name)) return Optional.of(c);
		}
		return Optional.empty();
	}

	public static Optional<Clan> byTag(String tag) {
		if (tag == null) return Optional.empty();
		for (Clan c : data().clans.values()) {
			if (c.tag.equalsIgnoreCase(tag)) return Optional.of(c);
		}
		return Optional.empty();
	}

	/** By name, then tag, case-insensitive. */
	public static Optional<Clan> find(String nameOrTag) {
		Optional<Clan> c = byName(nameOrTag);
		return c.isPresent() ? c : byTag(nameOrTag);
	}

	/** In cents. */
	public static long bankBalance(Clan c) {
		return c == null || c.bank == null ? 0 : EconomyService.balance(c.bank);
	}

	public static String bankAccountName(Clan c) {
		return BANK_NAME_PREFIX + c.id;
	}

	public static String nameError(String name) {
		if (name == null || name.length() < NAME_MIN || name.length() > NAME_MAX || !NAME.matcher(name).matches()) {
			return "Clan name: " + NAME_MIN + "–" + NAME_MAX + " characters, only letters, numbers and _.";
		}
		return null;
	}

	public static String tagError(String tag) {
		if (tag == null || tag.length() < TAG_MIN || tag.length() > TAG_MAX || !TAG.matcher(tag).matches()) {
			return "Clan tag: " + TAG_MIN + "–" + TAG_MAX + " characters, only letters and numbers.";
		}
		return null;
	}

	/** Also taken if it is another clan's tag, or /clan join|info <name|tag> would be ambiguous. */
	public static boolean nameTaken(String name) {
		return byName(name).isPresent() || byTag(name).isPresent();
	}

	/** Also taken if it is another clan's name. */
	public static boolean tagTaken(String tag) {
		return byTag(tag).isPresent() || byName(tag).isPresent();
	}

	public static boolean isInvited(Clan c, UUID player, long now) {
		Long until = c.invites.get(player);
		return until != null && until > now;
	}

	public static List<Clan> invitesFor(UUID player, long now) {
		List<Clan> list = new ArrayList<>();
		for (Clan c : data().clans.values()) {
			if (isInvited(c, player, now)) list.add(c);
		}
		return list;
	}

	public static List<Clan> openClans() {
		List<Clan> list = new ArrayList<>();
		for (Clan c : data().clans.values()) {
			if (c.open) list.add(c);
		}
		list.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));
		return list;
	}

	/** Leader, officers, members; by name within each. */
	public static List<Map.Entry<UUID, Member>> sortedMembers(Clan c) {
		List<Map.Entry<UUID, Member>> list = new ArrayList<>(c.members.entrySet());
		list.sort(Comparator.<Map.Entry<UUID, Member>>comparingInt(e -> -e.getValue().role.rank)
			.thenComparing(e -> e.getValue().name.toLowerCase(Locale.ROOT)));
		return list;
	}

	/** Leaderboard entry: bank plus members' wealth (account, plus bank feature balance if present). */
	public record TopEntry(Clan clan, long bank, long members, long total) {}

	public static List<TopEntry> top() {
		Optional<Services.Bank> bankService = Services.find(Services.Bank.class);
		List<TopEntry> list = new ArrayList<>();
		for (Clan c : data().clans.values()) {
			long bank = bankBalance(c);
			long sum = 0;
			for (UUID id : c.members.keySet()) {
				sum = addSat(sum, EconomyService.balance(id));
				if (bankService.isPresent()) {
					try {
						sum = addSat(sum, Math.max(0, bankService.get().balance(id)));
					} catch (RuntimeException e) {
						SmpEconomy.LOG.error("[SMPEco] clans: bank service threw", e);
					}
				}
			}
			list.add(new TopEntry(c, bank, sum, addSat(bank, sum)));
		}
		list.sort(Comparator.comparingLong(TopEntry::total).reversed().thenComparing(e -> e.clan().name.toLowerCase(Locale.ROOT)));
		return list;
	}

	private static long addSat(long a, long b) {
		long r = a + b;
		return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
	}

	public static Res create(UUID actor, String actorName, String name, String tag, String colorId, long now) {
		if (clanOf(actor).isPresent()) return fail(Code.ALREADY_IN_CLAN, "You're already in a clan.");
		String err = nameError(name);
		if (err != null) return fail(Code.INVALID_NAME, err);
		err = tagError(tag);
		if (err != null) return fail(Code.INVALID_TAG, err);
		ClanColor color = colorId == null ? ClanColor.orDefault(config().defaultColor) : ClanColor.byId(colorId).orElse(null);
		if (color == null) return fail(Code.INVALID_COLOR, "Unknown color. Available: " + String.join(", ", ClanColor.ids()));
		if (nameTaken(name)) return fail(Code.NAME_TAKEN, "The name §f" + name + "§c is already taken.");
		if (tagTaken(tag)) return fail(Code.TAG_TAKEN, "The tag §f" + tag + "§c is already taken.");
		// Fee last and atomically: everything else is checked, nothing can fail after this
		long fee = config().creationFee;
		if (fee > 0) {
			EconomyService.Result r = Treasury.collectFrom(actor, fee, TREASURY_REASON);
			if (!r.ok()) {
				if (r == EconomyService.Result.INSUFFICIENT_FUNDS) {
					return fail(Code.INSUFFICIENT_FUNDS, "Not enough money: creating a clan costs " + Msg.moneyStr(fee) + "§c, you have " + Msg.moneyStr(EconomyService.balance(actor)) + "§c.");
				}
				return fail(Code.FAILED, "Couldn't create the clan: " + r.message());
			}
		}
		Data d = data();
		Clan c = new Clan();
		c.id = d.nextId++;
		c.name = name;
		c.tag = tag;
		c.color = color.id;
		c.createdAt = now;
		c.bank = newBankId();
		c.members.put(actor, new Member(actorName, Role.LEADER, now));
		d.clans.put(c.id, c);
		EconomyService.registerSystemAccount(c.bank, bankAccountName(c));
		removeAllInvitesOf(actor);
		STORE.markDirty();
		ClanTeams.sync(c);
		SmpEconomy.LOG.info("[SMPEco] Clan #{} {} [{}] created by {} (fee {})", c.id, c.name, c.tag, actorName, Money.format(fee));
		return ok(c, "Clan " + c.display() + " §acreated!" + (fee > 0 ? " §7(fee " + Msg.moneyStr(fee) + ")" : ""), fee);
	}

	private static UUID newBankId() {
		UUID id;
		do {
			id = UUID.randomUUID();
		} while (EconomyService.hasAccount(id));
		return id;
	}

	public static Res invite(UUID actor, UUID target, String targetName, long now) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (!c.role(actor).atLeast(Role.OFFICER)) return fail(Code.NO_PERMISSION, "Only the leader and officers can invite players.", c);
		if (actor.equals(target)) return fail(Code.SELF, "You can't invite yourself.", c);
		Optional<Clan> other = clanOf(target);
		if (other.isPresent()) {
			return fail(Code.ALREADY_IN_CLAN, other.get() == c ? targetName + " is already in your clan." : targetName + " is already in a clan.", c);
		}
		if (c.members.size() >= config().maxMembers) return fail(Code.CLAN_FULL, "Your clan is full (" + config().maxMembers + " members).", c);
		purgeExpired(c, now);
		if (!c.invites.containsKey(target) && c.invites.size() >= MAX_INVITES) {
			return fail(Code.TOO_MANY_INVITES, "Too many pending invites.", c);
		}
		boolean renewed = c.invites.containsKey(target);
		c.invites.put(target, now + config().inviteExpiryMinutes * 60_000L);
		STORE.markDirty();
		return ok(c, (renewed ? "Invite to §f" + targetName + " §arenewed." : "§f" + targetName + " §ahas been invited."));
	}

	/** {@code clanFilter} null means the only invite. */
	public static Res accept(UUID actor, String actorName, String clanFilter, long now) {
		if (clanOf(actor).isPresent()) return fail(Code.ALREADY_IN_CLAN, "You're already in a clan.");
		List<Clan> invites = invitesFor(actor, now);
		Clan target;
		if (clanFilter != null) {
			Clan c = find(clanFilter).orElse(null);
			if (c == null) return fail(Code.NOT_FOUND, "Clan §f" + clanFilter + " §cnot found.");
			if (!invites.contains(c)) return fail(Code.NOT_INVITED, "You don't have a valid invite from " + c.display() + "§c.", c);
			target = c;
		} else {
			if (invites.isEmpty()) return fail(Code.NOT_INVITED, "You don't have a valid invite.");
			if (invites.size() > 1) return fail(Code.AMBIGUOUS, "Multiple invites: " + names(invites) + " §c– /clan accept <clan>");
			target = invites.get(0);
		}
		return addMember(target, actor, actorName, now);
	}

	/** {@code clanFilter} null means all. The Res carries the first declined clan. */
	public static Res deny(UUID actor, String clanFilter, long now) {
		List<Clan> invites = invitesFor(actor, now);
		List<Clan> denied = new ArrayList<>();
		if (clanFilter != null) {
			Clan c = find(clanFilter).orElse(null);
			if (c == null) return fail(Code.NOT_FOUND, "Clan §f" + clanFilter + " §cnot found.");
			if (!invites.contains(c)) return fail(Code.NOT_INVITED, "You don't have a valid invite from " + c.display() + "§c.", c);
			denied.add(c);
		} else {
			if (invites.isEmpty()) return fail(Code.NOT_INVITED, "You don't have a valid invite.");
			denied.addAll(invites);
		}
		for (Clan c : denied) c.invites.remove(actor);
		STORE.markDirty();
		return ok(denied.get(0), "Declined the invite from " + names(denied) + "§7.", denied.size());
	}

	/**
	 * Needs a valid invite or an open clan. Joining without an invite marks the member as {@link
	 * Member#openJoin}, so {@code /clan home} waits for {@link ClanConfig#openJoinHomeMinutes}.
	 */
	public static Res join(UUID actor, String actorName, String clanName, long now) {
		if (clanOf(actor).isPresent()) return fail(Code.ALREADY_IN_CLAN, "You're already in a clan.");
		Clan c = find(clanName).orElse(null);
		if (c == null) return fail(Code.NOT_FOUND, "Clan §f" + clanName + " §cnot found.");
		boolean invited = isInvited(c, actor, now);
		if (!c.open && !invited) {
			return fail(Code.NOT_INVITED, "The clan " + c.display() + " §cisn't open – you need an invite.", c);
		}
		return addMember(c, actor, actorName, now, !invited);
	}

	private static Res addMember(Clan c, UUID actor, String actorName, long now) {
		return addMember(c, actor, actorName, now, false);
	}

	private static Res addMember(Clan c, UUID actor, String actorName, long now, boolean openJoin) {
		if (c.members.size() >= config().maxMembers) {
			return fail(Code.CLAN_FULL, "The clan " + c.display() + " §cis full.", c);
		}
		Member m = new Member(actorName, Role.MEMBER, now);
		m.openJoin = openJoin;
		c.members.put(actor, m);
		removeAllInvitesOf(actor);
		STORE.markDirty();
		ClanTeams.sync(c);
		int lock = config().openJoinHomeMinutes;
		return ok(c, "You joined the clan " + c.display() + "§a!"
			+ (openJoin && lock > 0 ? " §7(Clan Home unlocks after " + lock + " min)" : ""));
	}

	/**
	 * Remaining {@code /clan home} lock in ms: only for role MEMBER who joined without an invite ({@link
	 * Member#openJoin}); 0 means allowed. Never longer than the configured duration, to survive clock jumps.
	 */
	public static long homeLockRemaining(Clan c, UUID player, long now) {
		Member m = c == null ? null : c.member(player);
		if (m == null || !m.openJoin || m.role.atLeast(Role.OFFICER)) return 0;
		long lock = config().openJoinHomeMinutes * 60_000L;
		if (lock <= 0) return 0;
		long left = m.joinedAt + lock - now;
		return Math.max(0, Math.min(lock, left));
	}

	public static String homeLockMessage(long remainingMs) {
		return "Joined without an invite: Clan Home unlocks after §f" + config().openJoinHomeMinutes + " min§c of membership"
			+ " – §f" + TimeUtil.formatDuration(Math.max(remainingMs, 1000)) + "§c left.";
	}

	private static void removeAllInvitesOf(UUID player) {
		for (Clan c : data().clans.values()) {
			if (c.invites.remove(player) != null) STORE.markDirty();
		}
	}

	/** True if anything changed. */
	public static boolean purgeExpired(long now) {
		boolean changed = false;
		for (Clan c : data().clans.values()) changed |= purgeExpired(c, now);
		return changed;
	}

	static boolean purgeExpired(Clan c, long now) {
		boolean changed = c.invites.values().removeIf(until -> until == null || until <= now);
		if (changed) STORE.markDirty();
		return changed;
	}

	public static Res leave(UUID actor) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) == Role.LEADER) {
			return fail(Code.LEADER_CANNOT_LEAVE, "As the leader you can't leave: hand over leadership (/clan promote <officer>) or disband the clan (/clan disband).", c);
		}
		removeMember(c, actor);
		return ok(c, "You left the clan " + c.display() + "§a.");
	}

	public static Res kick(UUID actor, UUID target) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (actor.equals(target)) return fail(Code.SELF, "You can't kick yourself.", c);
		Member t = c.member(target);
		if (t == null) return fail(Code.NOT_MEMBER, "That player isn't in your clan.", c);
		Role role = c.role(actor);
		if (!role.atLeast(Role.OFFICER) || role.rank <= t.role.rank) {
			return fail(Code.NO_PERMISSION, role.atLeast(Role.OFFICER) ? "You can only kick players with a lower rank." : "Only the leader and officers can kick players.", c);
		}
		removeMember(c, target);
		return ok(c, "§f" + t.name + " §awas kicked from the clan.");
	}

	private static void removeMember(Clan c, UUID player) {
		c.members.remove(player);
		data().chatToggled.remove(player);
		STORE.markDirty();
		ClanTeams.sync(c);
	}

	/**
	 * Leader only: member to officer; officer to leader (handover, only with {@code confirmed}; the old
	 * leader becomes officer). Without confirmation {@link Code#NEEDS_CONFIRM}.
	 */
	public static Res promote(UUID actor, UUID target, boolean confirmed) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) != Role.LEADER) return fail(Code.NO_PERMISSION, "Only the leader can promote members.", c);
		if (actor.equals(target)) return fail(Code.SELF, "You're already the leader.", c);
		Member t = c.member(target);
		if (t == null) return fail(Code.NOT_MEMBER, "That player isn't in your clan.", c);
		if (t.role == Role.MEMBER) {
			t.role = Role.OFFICER;
			STORE.markDirty();
			return ok(c, "§f" + t.name + " §ais now an Officer.");
		}
		if (!confirmed) return fail(Code.NEEDS_CONFIRM, "§f" + t.name + " §ebecomes the new Leader, you become an Officer.", c);
		t.role = Role.LEADER;
		c.members.get(actor).role = Role.OFFICER;
		STORE.markDirty();
		return ok(c, "§f" + t.name + " §ais now the Leader of the clan.");
	}

	/** Leader only. */
	public static Res demote(UUID actor, UUID target) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) != Role.LEADER) return fail(Code.NO_PERMISSION, "Only the leader can demote members.", c);
		if (actor.equals(target)) return fail(Code.SELF, "You can't demote yourself.", c);
		Member t = c.member(target);
		if (t == null) return fail(Code.NOT_MEMBER, "That player isn't in your clan.", c);
		if (t.role != Role.OFFICER) return fail(Code.ALREADY_ROLE, "§f" + t.name + " §cis already a Member.", c);
		t.role = Role.MEMBER;
		STORE.markDirty();
		return ok(c, "§f" + t.name + " §ais now a Member.");
	}

	public static Res toggleOpen(UUID actor) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) != Role.LEADER) return fail(Code.NO_PERMISSION, "Only the leader can change this.", c);
		c.open = !c.open;
		STORE.markDirty();
		if (!c.open) return ok(c, "Your clan is now §cclosed§7 – invite only.");
		int lock = config().openJoinHomeMinutes;
		return ok(c, "Your clan is now §aopen§7 – anyone can join with /clan join. New members: §f/clan home "
			+ (lock > 0 ? "§7after §f" + lock + " min§7" : "§eright away§7") + ", friendly fire off right away. Unwanted guests: §e/clan kick§7.");
	}

	public static Res setColor(UUID actor, String colorId) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) != Role.LEADER) return fail(Code.NO_PERMISSION, "Only the leader can change the color.", c);
		ClanColor color = ClanColor.byId(colorId).orElse(null);
		if (color == null) return fail(Code.INVALID_COLOR, "Unknown color. Available: " + String.join(", ", ClanColor.ids()), c);
		c.color = color.id;
		STORE.markDirty();
		ClanTeams.sync(c);
		return ok(c, "Tag color changed: " + c.coloredTag());
	}

	public static Res setHome(UUID actor, Home home) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (!c.role(actor).atLeast(Role.OFFICER)) return fail(Code.NO_PERMISSION, "Only the leader and officers can set the Clan Home.", c);
		c.home = Objects.requireNonNull(home);
		STORE.markDirty();
		return ok(c, "Clan Home set §7(" + home.shortString() + ")§a.");
	}

	/** Keeps the scoreboard team current. */
	public static void updateName(UUID player, String name) {
		Clan c = clanOf(player).orElse(null);
		if (c == null || name == null || name.isEmpty()) return;
		Member m = c.members.get(player);
		if (!name.equals(m.name)) {
			SmpEconomy.LOG.info("[SMPEco] Clan {}: Name {} → {}", c.name, m.name, name);
			m.name = name;
			STORE.markDirty();
		}
		ClanTeams.sync(c);
	}

	/**
	 * A player with this name just came online: other members (different UUID) still stored under that old
	 * name lose it, so their team entry does not land on the name's new owner. Their new name is filled in on
	 * their next join.
	 */
	public static void claimName(UUID player, String name) {
		if (name == null || name.isEmpty()) return;
		for (Clan c : data().clans.values()) {
			boolean changed = false;
			for (Map.Entry<UUID, Member> e : c.members.entrySet()) {
				if (!e.getKey().equals(player) && e.getValue().name.equalsIgnoreCase(name)) {
					SmpEconomy.LOG.info("[SMPEco] Clan {}: name {} now belongs to {}, entry of {} cleared", c.name, name, player, e.getKey());
					e.getValue().name = "";
					changed = true;
				}
			}
			if (changed) {
				STORE.markDirty();
				ClanTeams.sync(c);
			}
		}
	}

	public static Res deposit(UUID actor, String actorName, long amount, long now) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (!Money.isValidAmount(amount)) return fail(Code.INVALID_AMOUNT, "Invalid amount.", c);
		EconomyService.Result r = EconomyService.transfer(actor, c.bank, amount);
		if (!r.ok()) {
			return switch (r) {
				case INSUFFICIENT_FUNDS -> fail(Code.INSUFFICIENT_FUNDS, "Not enough money (you have " + Msg.moneyStr(EconomyService.balance(actor)) + "§c).", c);
				case LIMIT_EXCEEDED -> fail(Code.LIMIT_EXCEEDED, "The Clan Bank is full.", c);
				default -> bankFailure(c, r);
			};
		}
		addLog(c, now, actor, actorName, LogEntry.DEPOSIT, amount);
		return ok(c, "Deposited " + Msg.moneyStr(amount) + " §ainto the Clan Bank §7(balance " + Msg.moneyStr(bankBalance(c)) + ")§a.", amount);
	}

	/** Leader and officers only. */
	public static Res withdraw(UUID actor, String actorName, long amount, long now) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (!c.role(actor).atLeast(Role.OFFICER)) return fail(Code.NO_PERMISSION, "Only the leader and officers can withdraw.", c);
		if (!Money.isValidAmount(amount)) return fail(Code.INVALID_AMOUNT, "Invalid amount.", c);
		EconomyService.Result r = EconomyService.transfer(c.bank, actor, amount);
		if (!r.ok()) {
			return switch (r) {
				case INSUFFICIENT_FUNDS -> fail(Code.BANK_INSUFFICIENT, "The Clan Bank only has " + Msg.moneyStr(bankBalance(c)) + "§c.", c);
				case LIMIT_EXCEEDED -> fail(Code.LIMIT_EXCEEDED, "That would push your balance over the limit.", c);
				case NO_ACCOUNT -> fail(Code.FAILED, "Your account wasn't found.", c);
				default -> bankFailure(c, r);
			};
		}
		addLog(c, now, actor, actorName, LogEntry.WITHDRAW, amount);
		return ok(c, "Withdrew " + Msg.moneyStr(amount) + " §afrom the Clan Bank §7(balance " + Msg.moneyStr(bankBalance(c)) + ")§a.", amount);
	}

	private static Res bankFailure(Clan c, EconomyService.Result r) {
		if (r == EconomyService.Result.NO_ACCOUNT && !EconomyService.hasAccount(c.bank)) {
			// Account missing (should never happen): recreate as a system account and abort the booking
			SmpEconomy.LOG.error("[SMPEco] Clan #{}: bank account {} was missing, recreated", c.id, c.bank);
			EconomyService.registerSystemAccount(c.bank, bankAccountName(c));
		}
		return fail(Code.FAILED, "Transaction failed: " + r.message(), c);
	}

	private static void addLog(Clan c, long now, UUID actor, String name, String type, long amount) {
		LogEntry e = new LogEntry();
		e.time = now;
		e.actor = actor;
		e.name = name == null ? "" : name;
		e.type = type;
		e.amount = amount;
		e.balanceAfter = bankBalance(c);
		c.log.add(e);
		while (c.log.size() > LOG_SIZE) c.log.remove(0);
		STORE.markDirty();
	}

	/** The caller handles confirmation. */
	public static Res disband(UUID actor) {
		Clan c = clanOf(actor).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		if (c.role(actor) != Role.LEADER) return fail(Code.NO_PERMISSION, "Only the leader can disband the clan.", c);
		return dissolve(c);
	}

	/**
	 * Transfers the whole balance atomically to the leader (without one, on damaged data, to the treasury),
	 * then removes clan, team and the empty account. If the payout fails, everything stays. {@link
	 * Res#amount()} is the amount paid.
	 */
	public static Res dissolve(Clan c) {
		long balance = bankBalance(c);
		UUID leader = c.leaderId();
		if (balance > 0) {
			if (leader != null) {
				EconomyService.Result r = EconomyService.transfer(c.bank, leader, balance);
				if (!r.ok()) return fail(Code.FAILED, "Couldn't pay out the Clan Bank (" + r.message() + ") – the clan stays.", c);
			} else {
				EconomyService.Result r = EconomyService.withdraw(c.bank, balance);
				if (!r.ok()) return fail(Code.FAILED, "Couldn't empty the Clan Bank (" + r.message() + ").", c);
				EconomyService.Result back = Treasury.deposit(balance, TREASURY_REASON_ORPHAN);
				if (!back.ok()) {
					EconomyService.deposit(c.bank, balance); // Back to the bank account, which exists, so no starting balance applies
					return fail(Code.FAILED, "The Treasury didn't accept the money (" + back.message() + ").", c);
				}
			}
		}
		if (EconomyService.balance(c.bank) != 0) {
			// Must not happen (all on the server thread); never delete an account holding money
			SmpEconomy.LOG.error("[SMPEco] Clan #{}: bank not empty after payout ({}), disband aborted", c.id, EconomyService.balance(c.bank));
			return fail(Code.FAILED, "The Clan Bank isn't empty – disband cancelled.", c);
		}
		Data d = data();
		d.clans.remove(c.id);
		for (UUID m : c.members.keySet()) d.chatToggled.remove(m);
		EconomyService.deleteAccount(c.bank);
		EconomyService.unregisterSystemAccount(c.bank);
		STORE.markDirty();
		ClanTeams.remove(c);
		SmpEconomy.LOG.info("[SMPEco] Clan #{} {} [{}] disbanded, {} to {}", c.id, c.name, c.tag, Money.format(balance), leader == null ? Treasury.NAME : c.members.get(leader).name);
		return ok(c, "Clan " + c.display() + " §adisbanded." + (balance > 0 ? " §7The Clan Bank (" + Msg.moneyStr(balance) + ") went to " + (leader == null ? Treasury.NAME : c.members.get(leader).name) + "." : ""), balance);
	}

	/** Removes a player from their clan; if they lead it, disbands it with the bank going to them. */
	public static Res adminReset(UUID player) {
		Clan c = clanOf(player).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "That player isn't in a clan.");
		if (c.role(player) == Role.LEADER) return dissolve(c);
		removeMember(c, player);
		return ok(c, "Removed the player from " + c.display() + "§a.");
	}

	public static boolean chatToggled(UUID player) {
		return data().chatToggled.contains(player);
	}

	/** Res.amount is 1 if now on. */
	public static Res toggleChat(UUID player) {
		Data d = data();
		if (d.chatToggled.remove(player)) {
			STORE.markDirty();
			return ok(clanOf(player).orElse(null), "Clan Chat §coff§7 – your messages go to everyone again.", 0);
		}
		Clan c = clanOf(player).orElse(null);
		if (c == null) return fail(Code.NOT_IN_CLAN, "You're not in a clan.");
		d.chatToggled.add(player);
		STORE.markDirty();
		return ok(c, "Clan Chat §aon§7 – your messages only go to your clan. §8(/clan chat to turn it off)", 1);
	}

	private static String names(List<Clan> clans) {
		List<String> list = new ArrayList<>();
		for (Clan c : clans) list.add(c.display() + "§7");
		return String.join("§7, ", list);
	}

	/** After load: repairs nulls, exactly one leader, no double memberships, registers bank accounts. */
	static void repair(Data d, String source) {
		boolean changed = false;
		if (d.clans == null) {
			d.clans = new LinkedHashMap<>();
			changed = true;
		}
		if (d.chatToggled == null) {
			d.chatToggled = new HashSet<>();
			changed = true;
		}
		changed |= d.clans.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		Set<UUID> seen = new HashSet<>();
		int maxId = 0;
		for (Map.Entry<Integer, Clan> e : d.clans.entrySet()) {
			Clan c = e.getValue();
			if (c.id != e.getKey()) {
				c.id = e.getKey();
				changed = true;
			}
			maxId = Math.max(maxId, c.id);
			if (c.name == null || c.name.isBlank()) {
				c.name = "Clan" + c.id;
				changed = true;
			}
			if (c.tag == null || c.tag.isBlank()) {
				c.tag = "C" + c.id;
				changed = true;
			}
			if (ClanColor.byId(c.color).isEmpty()) {
				c.color = ClanColor.DEFAULT.id;
				changed = true;
			}
			if (c.members == null) {
				c.members = new LinkedHashMap<>();
				changed = true;
			}
			int before = c.members.size();
			c.members.entrySet().removeIf(m -> m.getKey() == null || m.getValue() == null || !seen.add(m.getKey()));
			if (c.members.size() != before) {
				SmpEconomy.LOG.warn("[SMPEco] {}: clan #{} had invalid or duplicate members, removed", source, c.id);
				changed = true;
			}
			for (Member m : c.members.values()) {
				if (m.name == null) {
					m.name = "";
					changed = true;
				}
				if (m.role == null) {
					m.role = Role.MEMBER;
					changed = true;
				}
			}
			changed |= fixLeader(c, source);
			if (c.invites == null) {
				c.invites = new LinkedHashMap<>();
				changed = true;
			}
			changed |= c.invites.entrySet().removeIf(i -> i.getKey() == null || i.getValue() == null);
			if (c.log == null) {
				c.log = new ArrayList<>();
				changed = true;
			}
			changed |= c.log.removeIf(Objects::isNull);
			while (c.log.size() > LOG_SIZE) {
				c.log.remove(0);
				changed = true;
			}
			if (c.home != null && (c.home.dim == null || c.home.dim.isBlank())) {
				c.home = null;
				changed = true;
			}
			if (c.bank == null) {
				c.bank = UUID.randomUUID();
				SmpEconomy.LOG.error("[SMPEco] {}: clan #{} without bank account, new (empty) account created", source, c.id);
				changed = true;
			}
			if (c.members.isEmpty()) SmpEconomy.LOG.error("[SMPEco] {}: clan #{} {} has no members (admin: /clan admin disband)", source, c.id, c.name);
		}
		if (d.nextId <= maxId) {
			d.nextId = maxId + 1;
			changed = true;
		}
		changed |= d.chatToggled.removeIf(u -> u == null || clanOf(d, u).isEmpty());
		for (Clan c : d.clans.values()) EconomyService.registerSystemAccount(c.bank, bankAccountName(c));
		if (changed) STORE.markDirty();
	}

	/**
	 * If missing, the longest-serving officer (else member) becomes leader; if several, only the first stays.
	 */
	static boolean fixLeader(Clan c, String source) {
		boolean changed = false;
		UUID leader = null;
		for (Map.Entry<UUID, Member> e : c.members.entrySet()) {
			if (e.getValue().role != Role.LEADER) continue;
			if (leader == null) {
				leader = e.getKey();
			} else {
				e.getValue().role = Role.OFFICER;
				changed = true;
			}
		}
		if (leader == null && !c.members.isEmpty()) {
			Map.Entry<UUID, Member> pick = null;
			for (Map.Entry<UUID, Member> e : c.members.entrySet()) {
				if (e.getValue().role == Role.OFFICER) {
					pick = e;
					break;
				}
			}
			if (pick == null) pick = c.members.entrySet().iterator().next();
			pick.getValue().role = Role.LEADER;
			SmpEconomy.LOG.warn("[SMPEco] {}: clan #{} without leader, {} is now leader", source, c.id, pick.getValue().display());
			changed = true;
		}
		return changed;
	}

	/**
	 * Orphaned clan bank accounts (UUID to name): a "Clan-Bank #N" account in economy.json that no clan in
	 * clans.json points to, e.g. after DataStore restored clans.json from backup while economy.json was
	 * current. Members deposited that money, so it stays untouched until an admin pays it out with {@link
	 * #resolveOrphan}. In memory only, like system accounts themselves, so {@link #scanOrphanBanks} finds
	 * them again on each start (until registered they appear in {@code EconomyService.top()}).
	 */
	private static final Map<UUID, String> ORPHANS = new LinkedHashMap<>();

	public record Orphan(UUID id, String name, long balance) {}

	private static Set<UUID> clanBanks() {
		Set<UUID> banks = new HashSet<>();
		for (Clan c : data().clans.values()) {
			if (c.bank != null) banks.add(c.bank);
		}
		return banks;
	}

	/**
	 * After server start (economy.json and clans.json loaded) and before /clan admin orphan: registers
	 * "Clan-Bank #..." accounts without a clan as system accounts, which hides them from /baltop,
	 * leaderboards, holograms, {@code rank()} and name completion, and remembers them for payout. Player
	 * names cannot contain a space, so the prefix never hits a player account. Returns the count of newly
	 * found accounts.
	 */
	static int scanOrphanBanks() {
		if (!STORE.isLoaded()) return 0;
		Set<UUID> banks = clanBanks();
		ORPHANS.keySet().removeIf(id -> {
			if (banks.contains(id)) return true; // Belongs to a clan (again); its registration stays
			if (EconomyService.hasAccount(id)) return false;
			EconomyService.unregisterSystemAccount(id);
			return true;
		});
		int found = 0;
		for (EconomyService.Entry e : EconomyService.top()) { // top() only contains unregistered accounts
			if (e.name() == null || !e.name().startsWith(BANK_NAME_PREFIX) || banks.contains(e.id())) continue;
			ORPHANS.put(e.id(), e.name());
			EconomyService.registerSystemAccount(e.id(), e.name());
			found++;
			SmpEconomy.LOG.error("[SMPEco] Orphaned clan bank account {} ({}) with {}: no matching clan in clans.json. Hidden from leaderboards;"
				+ " pay out with /clan admin orphan {} <treasury|player <name>>", e.name(), e.id(), Money.format(e.balance()), e.id());
		}
		return found;
	}

	public static List<Orphan> orphans() {
		List<Orphan> list = new ArrayList<>();
		ORPHANS.forEach((id, name) -> {
			if (EconomyService.hasAccount(id)) list.add(new Orphan(id, name, EconomyService.balance(id)));
		});
		list.sort(Comparator.comparingLong(Orphan::balance).reversed().thenComparing(o -> o.id().toString()));
		return list;
	}

	public static boolean isOrphan(UUID id) {
		return id != null && ORPHANS.containsKey(id);
	}

	/**
	 * Transfers the whole balance atomically to {@code target} (player with an account), or to the treasury
	 * if null, then deletes the empty account. If the transfer fails, everything stays. Only accounts from
	 * {@link #scanOrphanBanks}, never a live clan's bank. {@link Res#amount()} is the amount paid.
	 */
	public static Res resolveOrphan(UUID bank, UUID target) {
		if (bank == null || !ORPHANS.containsKey(bank)) return fail(Code.NOT_FOUND, "No orphaned Clan Bank account with that ID. §7(/clan admin orphan)");
		String name = ORPHANS.get(bank);
		if (clanBanks().contains(bank)) {
			ORPHANS.remove(bank);
			return fail(Code.FAILED, "That account belongs to a clan – it isn't orphaned.");
		}
		if (!EconomyService.hasAccount(bank)) {
			ORPHANS.remove(bank);
			EconomyService.unregisterSystemAccount(bank);
			return fail(Code.NOT_FOUND, "That account doesn't exist anymore.");
		}
		if (target != null && (target.equals(bank) || EconomyService.isSystemAccount(target))) {
			return fail(Code.FAILED, "The recipient has to be a player.");
		}
		long balance = EconomyService.balance(bank);
		if (balance > 0) {
			if (target != null) {
				EconomyService.Result r = EconomyService.transfer(bank, target, balance);
				if (!r.ok()) return fail(Code.FAILED, "Payout failed: " + r.message());
			} else {
				EconomyService.Result r = EconomyService.withdraw(bank, balance);
				if (!r.ok()) return fail(Code.FAILED, "Couldn't empty the account: " + r.message());
				EconomyService.Result back = Treasury.deposit(balance, TREASURY_REASON_ORPHAN);
				if (!back.ok()) {
					EconomyService.deposit(bank, balance); // Back to the bank account, which exists, so no starting balance applies
					return fail(Code.FAILED, "The Treasury didn't accept the money (" + back.message() + ").");
				}
			}
		}
		if (EconomyService.balance(bank) != 0) {
			SmpEconomy.LOG.error("[SMPEco] Orphaned clan bank account {}: not empty after payout ({}), not deleted", bank, EconomyService.balance(bank));
			return fail(Code.FAILED, "The account isn't empty after the payout – not deleted.");
		}
		EconomyService.deleteAccount(bank);
		EconomyService.unregisterSystemAccount(bank);
		ORPHANS.remove(bank);
		String to = target == null ? Treasury.NAME : EconomyService.nameOrUnknown(target);
		SmpEconomy.LOG.info("[SMPEco] Orphaned clan bank account {} ({}) resolved: {} to {}", name, bank, Money.format(balance), to);
		return ok(null, "§f" + name + " §aclosed" + (balance > 0 ? ": " + Msg.moneyStr(balance) + " §ato §f" + to + "§a." : " §7(empty)§a."), balance);
	}

	/** Selftests only: the test removes the account itself. */
	static void forgetOrphanForTests(UUID id) {
		ORPHANS.remove(id);
	}

	/**
	 * Selftests only, test player clans only: removes the clan right away without payout, deleting the
	 * account and its test money.
	 */
	static void purgeForTests(Clan c) {
		Data d = data();
		d.clans.remove(c.id);
		for (UUID m : c.members.keySet()) d.chatToggled.remove(m);
		if (c.bank != null) {
			EconomyService.deleteAccount(c.bank);
			EconomyService.unregisterSystemAccount(c.bank);
		}
		STORE.markDirty();
		ClanTeams.remove(c);
	}

	/** Selftests only: removes a test player from all clans and deletes clans they lead. */
	static void purgeTestPlayer(UUID player) {
		for (Clan c : List.copyOf(data().clans.values())) {
			if (c.invites.remove(player) != null) STORE.markDirty();
			if (!c.members.containsKey(player)) continue;
			if (c.role(player) == Role.LEADER) {
				purgeForTests(c);
			} else {
				removeMember(c, player);
			}
		}
		if (data().chatToggled.remove(player)) STORE.markDirty();
	}
}
