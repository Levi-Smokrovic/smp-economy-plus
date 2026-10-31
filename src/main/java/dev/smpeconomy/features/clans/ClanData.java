package dev.smpeconomy.features.clans;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent clan data ({@code <world>/smpeconomy/clans.json}). Bank money is not here but on system account
 * {@link Clan#bank} in {@code economy.json}, so deposits and withdrawals are atomic transfers within one
 * file.
 */
public final class ClanData {
	private ClanData() {}

	/** Descending by rank. */
	public enum Role {
		LEADER("Leader", "§6★ ", 3),
		OFFICER("Officer", "§e☆ ", 2),
		MEMBER("Member", "", 1);

		public final String label;
		/** Symbol before the name in clan chat and GUI, including color and space; empty for members. */
		public final String symbol;
		public final int rank;

		Role(String label, String symbol, int rank) {
			this.label = label;
			this.symbol = symbol;
			this.rank = rank;
		}

		public boolean atLeast(Role other) {
			return rank >= other.rank;
		}
	}

	public static final class Data {
		/** Never reused, so team names {@code tri_clan_<id>} stay unique. */
		public int nextId = 1;
		public Map<Integer, Clan> clans = new LinkedHashMap<>();
		/** Players with clan chat mode on (/clan chat): normal chat goes to the clan. */
		public Set<UUID> chatToggled = new HashSet<>();
	}

	public static final class Clan {
		public int id;
		public String name = "";
		public String tag = "";
		public String color = ClanColor.DEFAULT.id;
		/** System account of the clan bank (random UUID, name "Clan-Bank #id"). */
		public UUID bank;
		/** Anyone without a clan may join with /clan join. */
		public boolean open;
		public long createdAt;
		/** In join order. Exactly one has role LEADER. */
		public Map<UUID, Member> members = new LinkedHashMap<>();
		/** Player to expiry (epoch ms). */
		public Map<UUID, Long> invites = new LinkedHashMap<>();
		public Home home;
		/** Oldest first, at most {@link ClanService#LOG_SIZE}. */
		public List<LogEntry> log = new ArrayList<>();

		public ClanColor colorEnum() {
			return ClanColor.orDefault(color);
		}

		/** {@code §7[§eTAG§7]} */
		public String coloredTag() {
			return "§7[" + colorEnum().code + tag + "§7]";
		}

		/** {@code §7[§eTAG§7] §fName} */
		public String display() {
			return coloredTag() + " §f" + name;
		}

		public Member member(UUID id) {
			return id == null ? null : members.get(id);
		}

		public Role role(UUID id) {
			Member m = member(id);
			return m == null ? null : m.role;
		}

		public UUID leaderId() {
			for (Map.Entry<UUID, Member> e : members.entrySet()) {
				if (e.getValue().role == Role.LEADER) return e.getKey();
			}
			return null;
		}

		public String leaderName() {
			UUID id = leaderId();
			return id == null ? "?" : members.get(id).display();
		}

		/** By name, case-insensitive, also offline. */
		public Optional<UUID> memberByName(String name) {
			if (name == null) return Optional.empty();
			String key = name.toLowerCase(Locale.ROOT);
			for (Map.Entry<UUID, Member> e : members.entrySet()) {
				if (e.getValue().name.toLowerCase(Locale.ROOT).equals(key)) return Optional.of(e.getKey());
			}
			return Optional.empty();
		}

		public int count(Role role) {
			int n = 0;
			for (Member m : members.values()) {
				if (m.role == role) n++;
			}
			return n;
		}
	}

	public static final class Member {
		public String name = "";
		public Role role = Role.MEMBER;
		public long joinedAt;
		/**
		 * Joined an open clan through {@code /clan join} without an invite: {@code /clan home} only after
		 * {@link ClanConfig#openJoinHomeMinutes} from {@link #joinedAt} (officers and leader exempt).
		 */
		public boolean openJoin;

		public Member() {}

		public Member(String name, Role role, long joinedAt) {
			this.name = name;
			this.role = role;
			this.joinedAt = joinedAt;
		}

		/** "?" until known, e.g. after a name change until the next join. */
		public String display() {
			return name == null || name.isEmpty() ? "?" : name;
		}
	}

	public static final class Home {
		/** E.g. {@code minecraft:overworld}. */
		public String dim = "minecraft:overworld";
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;

		public String shortString() {
			return Math.round(Math.floor(x)) + ", " + Math.round(Math.floor(y)) + ", " + Math.round(Math.floor(z));
		}
	}

	public static final class LogEntry {
		public static final String DEPOSIT = "deposit";
		public static final String WITHDRAW = "withdraw";

		public long time;
		public UUID actor;
		public String name = "";
		public String type = DEPOSIT;
		public long amount;
		public long balanceAfter;

		public String label() {
			return WITHDRAW.equals(type) ? "Withdrawal" : "Deposit";
		}
	}
}
