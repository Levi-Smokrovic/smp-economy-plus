package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.features.minigames.TreasuryRewards;

/**
 * Fairness: only players online at the start with chat games on, enough playtime, and (on real public IPs) the
 * only participating account of their IP can play ({@link Env#exclusions}). Turning chat games off during the
 * question drops out. Messages that could reveal the solution stay private, and which ones never depends on whether
 * they are correct ({@link MegaQuestion#read}), otherwise "disappears/appears" would be an oracle with no attempt
 * limit. Answers count only after a random secret wait; too-early attempts are used up (against bots firing at a
 * fixed time). The rule line names only the upper bound, never the drawn wait. Only the first attempt per player
 * and question counts. The solution reaches the log only after the end.
 * <p>Exactly one payout: on a win the game ends, the payout is recorded by id in {@code megachallenge.json} and the
 * money is booked in the same tick, all in one storage journal (all or nothing). A running challenge is stored
 * without its solution; a restart aborts it with no payout, and a recorded id is never paid again.
 * <p>Normal chat games pause during the challenge; a due challenge waits for a running chat game to finish. Server
 * thread only.
 */
public final class MegaChallenge {
	public static final String WINS_COUNTER = "minigames.mega_wins";
	public static final String PREFIX = "§6§l⚡ MEGA §8» §7";
	private static final int MAX_PAYOUT_RECORDS = 100;

	public enum Type {
		MATH("math", "Math"),
		TRIVIA("trivia", "Minecraft Trivia"),
		RACE("race", "Race"),
		RIDDLE("riddle", "Riddle"),
		UNSCRAMBLE("unscramble", "Word Scramble");

		public final String id;
		public final String command;
		public final String label;

		Type(String id, String label) {
			this.id = id;
			this.command = id;
			this.label = label;
		}

		public static Type parse(String s) {
			if (s == null) return null;
			for (Type t : values()) {
				if (t.id.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) return t;
			}
			return null;
		}

		public static List<String> ids() {
			return Arrays.stream(values()).map(t -> t.id).toList();
		}
	}

	public enum Phase {
		IDLE("idle"), ANNOUNCE("announce"), QUESTION("question"), RACE("race");

		public final String id;

		Phase(String id) {
			this.id = id;
		}
	}

	public enum Sound { ANNOUNCE, START, WIN, END }

	public interface Output {
		void broadcast(Component message);

		void send(UUID player, Component message);

		void title(Component title, Component subtitle, Sound sound);

		void bar(Component name, float progress, Phase phase);

		void hideBar();

		void actionBar(UUID player, Component message);
	}

	public interface Env {
		Set<UUID> eligible();

		/** Who among {@code eligible} may not take part, with the reason for the player. */
		Map<UUID, String> exclusions(Set<UUID> eligible);

		boolean chatGamesOn(UUID player);

		boolean chatGameRunning();

		void stopChatGame(String message);

		void postponeChatGames(long until);

		Prepared prepare(Type type, String variant, Random random);

		RaceProbe raceProbe();

		long nightMillisLeft();
	}

	public interface RaceProbe {
		void begin(MegaConfig.Race race, Set<UUID> participants);

		/** -1 means not measurable right now (offline). */
		long progress(UUID player);

		/** Live every 5 ticks: tracks movement and look direction of participants, against AFK farms. */
		void sample(long now);

		boolean canWin(UUID player, long now);

		void end();
	}

	public record Prepared(Type type, String variant, String variantLabel, MegaQuestion question, MegaConfig.Race race) {
		public static Prepared of(MegaQuestion q) {
			return new Prepared(q.type(), q.variant(), q.variantLabel(), q, null);
		}

		public static Prepared of(MegaConfig.Race race) {
			return new Prepared(Type.RACE, race.id, race.label, null, race);
		}
	}

	public enum Outcome { PASS, NOT_PARTICIPANT, LATE, UNCLEAR, TOO_EARLY, TOO_FAST, NO_TRIES, WRONG, WON }

	/** {@code suppress}: don't show publicly, it could reveal the solution. */
	public record GuessResult(Outcome outcome, boolean suppress, long paid, long pending, boolean key) {
		static final GuessResult PASS = new GuessResult(Outcome.PASS, false, 0, 0, false);

		static GuessResult of(Outcome o) {
			return new GuessResult(o, true, 0, 0, false);
		}
	}

	public static final class Data {
		/** Start of the last challenge in epoch ms, the basis for scheduling. */
		public long lastRunAt;
		public long seq;
		/** Without the solution; only here so a restart can abort it cleanly. */
		public ActiveInfo active;
		/** Recent payouts, at most one per challenge id. */
		public List<Payout> payouts = new ArrayList<>();

		public void fixNulls() {
			if (payouts == null) payouts = new ArrayList<>();
			payouts.removeIf(p -> p == null);
		}
	}

	public static final class ActiveInfo {
		public long id;
		public String type = "";
		public String variant = "";
		public String phase = "";
		public long startedAt;
		public long questionAt;
		public long endsAt;
	}

	public static final class Payout {
		public long id;
		public String winner = "";
		public String name = "";
		public String type = "";
		public String variant = "";
		public long amount;
		public long pending;
		public boolean key;
		public long at;
		/** Ms between answer unlock and the correct answer; very small suggests a bot. */
		public long msAfterOpen = -1;
		public boolean bonus;
	}

	private static final class Guesses {
		long lastAt;
		int count;
	}

	private final Supplier<Data> data;
	private final Runnable markDirty;
	private final Supplier<MegaConfig> config;
	private final LongSupplier clock;
	private final Random random;
	private final Output out;
	private final Env env;
	private DoubleSupplier keyChanceOverride;

	private Phase phase = Phase.IDLE;
	private Prepared current;
	private long id;
	private long announcedAt;
	private long questionAt;
	/** Random and secret; only RCON sees it. */
	private long answersOpenAt;
	private long endsAt;
	private final Set<UUID> participants = new LinkedHashSet<>();
	private final Map<UUID, String> excluded = new HashMap<>();
	private final Set<UUID> dropped = new HashSet<>();
	private final Map<UUID, Long> noticeAt = new HashMap<>();
	private final Map<UUID, Guesses> guesses = new HashMap<>();
	private int hintsShown;
	private boolean reminded;
	private long nextAt = -1;
	private long serverStartedAt;
	private List<Component> pendingExplanation;
	private long explainAt;
	private long lastProgressAt;
	private long lastRaceLogAt;
	private boolean bonus;
	private long reward;

	public MegaChallenge(Supplier<Data> data, Runnable markDirty, Supplier<MegaConfig> config, LongSupplier clock, Random random, Output out, Env env) {
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.clock = clock;
		this.random = random;
		this.out = out;
		this.env = env;
	}

	public void setKeyChanceOverride(DoubleSupplier chance) {
		this.keyChanceOverride = chance;
	}

	public Phase phase() {
		return phase;
	}

	public boolean busy() {
		return phase != Phase.IDLE;
	}

	public Prepared current() {
		return current;
	}

	public long id() {
		return id;
	}

	public long questionAt() {
		return questionAt;
	}

	public long answersOpenAt() {
		return answersOpenAt;
	}

	public long endsAt() {
		return endsAt;
	}

	public long nextAt() {
		return nextAt;
	}

	public Set<UUID> participants() {
		return Collections.unmodifiableSet(participants);
	}

	public int hintsShown() {
		return hintsShown;
	}

	public boolean bonus() {
		return bonus;
	}

	public long reward() {
		return reward;
	}

	public Data data() {
		return data.get();
	}

	public void onServerStarted() {
		long now = clock.getAsLong();
		serverStartedAt = now;
		Data d = data.get();
		if (d.active != null) {
			SmpEconomy.LOG.warn("[SMPEco] MEGA-CHALLENGE #{} ({}/{}) aborted by the restart, no payout", d.active.id, d.active.type,
				d.active.variant);
			d.active = null;
			markDirty.run();
		}
		resetRuntime();
		nextAt = computeNextAt(now);
	}

	public void onServerStopping() {
		if (phase == Phase.IDLE) return;
		long cid = id;
		Prepared p = current;
		endRuntime();
		out.broadcast(prefixed("§7The MEGA-CHALLENGE was cancelled because the server is restarting."));
		SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} aborted on stop{}", cid, p != null && p.question() != null ? " (solution: " + p.question().solutionPlain() + ")" : "");
	}

	/**
	 * {@code intervalMinutes} after the last start (survives restarts), or after server start if there was none;
	 * never earlier than {@code minMinutesAfterStart} after server start.
	 */
	public long computeNextAt(long now) {
		MegaConfig c = config.get();
		long interval = c.intervalMinutes * 60_000L;
		long last = data.get().lastRunAt;
		long base = last > 0 ? last + interval : serverStartedAt + interval;
		return Math.max(base, serverStartedAt + c.minMinutesAfterStart * 60_000L);
	}

	public String start(Type type, String variant, boolean announce) {
		return start(type, variant, announce, false);
	}

	public String start(Type type, String variant, boolean announce, boolean bonus) {
		if (phase != Phase.IDLE) return "A MEGA-CHALLENGE is already running.";
		if (bonus && type == Type.RACE) return "A BONUS challenge is always a question, never a race.";
		MegaConfig c = config.get();
		Prepared p;
		try {
			Type t = type != null ? type : bonus ? pickQuestionType(c) : pickType(c);
			if (t == null) return "No type with weight > 0 available.";
			try {
				p = env.prepare(t, variant, random);
			} catch (IllegalStateException e) {
				if (type != null) throw e;
				SmpEconomy.LOG.warn("[SMPEco] MEGA-CHALLENGE: {} not possible ({}), using math instead", t.id, e.getMessage());
				p = env.prepare(Type.MATH, null, random);
			}
		} catch (IllegalArgumentException | IllegalStateException e) {
			return e.getMessage() == null ? e.toString() : e.getMessage();
		}
		long now = clock.getAsLong();
		if (pendingExplanation != null) {
			// still show the reveal of the previous challenge before the new one starts
			for (Component line : pendingExplanation) out.broadcast(Msg.legacy("§6§lSolution §8» ").append(line));
			pendingExplanation = null;
		}
		if (env.chatGameRunning()) env.stopChatGame("§7The chat game was ended for the §6§lMEGA-CHALLENGE§7.");
		Data d = data.get();
		d.seq++;
		d.lastRunAt = now;
		resetRuntime();
		id = d.seq;
		current = p;
		announcedAt = now;
		this.bonus = bonus && p.race() == null;
		this.reward = this.bonus ? c.bonusReward : c.reward;
		SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} started: {}/{} (announcement: {}{})", id, p.type().id, p.variant(), announce && c.announceSeconds > 0,
			this.bonus ? ", BONUS " + Money.format(reward) : "");
		if (announce && c.announceSeconds > 0) {
			phase = Phase.ANNOUNCE;
			questionAt = now + c.announceSeconds * 1000L;
			endsAt = questionAt;
			saveActive();
			out.title(Msg.legacy(this.bonus ? "§d§lBONUS QUESTION" : "§6§lMEGA-CHALLENGE"), Msg.legacy(Msg.moneyStr(reward) + " §efor the fastest solution!"),
				Sound.ANNOUNCE);
			out.broadcast(prefixed((this.bonus ? "§d§lBONUS QUESTION §8» " : "") + "§e§lThe MEGA-CHALLENGE starts in " + c.announceSeconds
				+ " seconds! §7Category: §f" + p.type().label + " §8• §7Prize: " + Msg.moneyStr(reward)));
			out.broadcast(prefixed(noHelpLine(c, p)));
			String who = c.minPlaytimeMinutes > 0 ? " (with at least " + c.minPlaytimeMinutes + " min playtime)" : "";
			out.broadcast(prefixed(p.race() != null
				? "§7Race: everyone who is online at the start and has chat games on can join" + who + "."
				: "§7Answer in chat – only you see your attempts. Everyone who is online at the start can join" + who + "."));
			updateBar(now);
		} else {
			begin(now);
		}
		nextAt = computeNextAt(now);
		return null;
	}

	public boolean stop(String message) {
		if (phase == Phase.IDLE) return false;
		long now = clock.getAsLong();
		Prepared p = current;
		boolean revealed = phase == Phase.QUESTION;
		long cid = id;
		endRuntime();
		out.broadcast(prefixed(message));
		if (revealed && p.question() != null) revealSolution(p.question(), now);
		env.postponeChatGames(now + config.get().chatGamePauseMinutes * 60_000L);
		nextAt = computeNextAt(now);
		SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} aborted{}", cid, p.question() != null ? " (solution: " + p.question().solutionPlain() + ")" : "");
		return true;
	}

	private void begin(long now) {
		Set<UUID> elig = env.eligible();
		if (elig.isEmpty()) {
			SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} skipped: nobody online with chat games on", id);
			endRuntime();
			return;
		}
		Map<UUID, String> ex = env.exclusions(elig);
		participants.clear();
		for (UUID p : elig) {
			if (!ex.containsKey(p)) participants.add(p);
		}
		excluded.clear();
		excluded.putAll(ex);
		if (participants.isEmpty()) {
			SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} skipped: nobody may take part ({} excluded)", id, ex.size());
			endRuntime();
			out.broadcast(prefixed("§7The MEGA-CHALLENGE is cancelled – nobody can take part right now."));
			for (Map.Entry<UUID, String> e : ex.entrySet()) out.send(e.getKey(), prefixed("§7You couldn't take part: §f" + e.getValue() + "§7."));
			return;
		}
		MegaConfig c = config.get();
		questionAt = now;
		Prepared p = current;
		if (p.race() != null) {
			long raceMs = p.race().minutes * 60_000L;
			if (p.race().night) {
				long night = env.nightMillisLeft();
				if (night < 60_000) {
					SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} skipped: night race {}, but only {} s of night left", id, p.race().id, night / 1000);
					endRuntime();
					out.broadcast(prefixed("§7The night is almost over – the night hunt is cancelled."));
					return;
				}
				raceMs = Math.min(raceMs, night);
			}
			phase = Phase.RACE;
			endsAt = now + raceMs;
			answersOpenAt = now;
			lastRaceLogAt = now;
			env.raceProbe().begin(p.race(), Set.copyOf(participants));
			out.title(Msg.legacy("§c§lMEGA RACE"), Msg.legacy("§e" + p.race().label), Sound.START);
			out.broadcast(prefixed("§c§lRACE §8» §f§l" + p.race().label + " §7– Time: §f" + minutesText(raceMs)));
			out.broadcast(prefixed("§7Counting starts now. The first to make it wins " + Msg.moneyStr(reward)
				+ "§7! §8(Progress: /chatgame mega · only players who move can win – no AFK farms)"));
			out.broadcast(prefixed(noHelpLine(c, p)));
		} else {
			phase = Phase.QUESTION;
			endsAt = now + c.answerSeconds * 1000L;
			long minMs = c.minAnswerDelaySeconds * 1000L;
			long maxMs = Math.max(minMs, c.maxAnswerDelaySeconds * 1000L);
			answersOpenAt = now + minMs + (maxMs > minMs ? (long) (random.nextDouble() * (maxMs - minMs)) : 0);
			MegaQuestion q = p.question();
			out.title(Msg.legacy(bonus ? "§d§lBONUS QUESTION" : "§6§lMEGA-CHALLENGE"), Msg.legacy("§eThe challenge is in chat!"), Sound.START);
			out.broadcast(prefixed((bonus ? "§d§lBONUS QUESTION §8» " : "") + "§e§l" + title(p) + " §8• §7Prize: " + Msg.moneyStr(reward)));
			for (Component line : q.lines()) out.broadcast(prefixed(line));
			out.broadcast(prefixed(rules(c, q)));
		}
		for (Map.Entry<UUID, String> e : ex.entrySet()) {
			out.send(e.getKey(), prefixed("§7You can't win this MEGA-CHALLENGE: §f" + e.getValue() + "§7."));
		}
		saveActive();
		updateBar(now);
	}

	static int maxGuesses(MegaConfig c, MegaQuestion q) {
		if (q.maxGuesses() >= 0) return q.maxGuesses();
		return q.numeric() ? c.maxGuesses : c.maxTextGuesses;
	}

	/**
	 * Names only the upper bound of the secret wait ("up to 30 s"), never the drawn time: with one attempt nobody
	 * should lose their answer to the wait unawares.
	 */
	private String rules(MegaConfig c, MegaQuestion q) {
		int max = maxGuesses(c, q);
		StringBuilder sb = new StringBuilder("§c§lNo external help §8• §7Answer in chat (stays private): ")
			.append(q.numeric() ? (q.digitSequence() ? "just the digits" : "just the number") : "just the term");
		if (max == 1) sb.append(" §8• §e§lonly your FIRST answer counts");
		else if (max > 1) sb.append(" §8• §7").append(max).append(" attempts");
		if (c.maxAnswerDelaySeconds > 0) {
			sb.append(" §8• §7answers only count after a secret wait (up to ").append(c.maxAnswerDelaySeconds).append(" s) – answering earlier uses up ")
				.append(max == 1 ? "your attempt" : "an attempt");
		}
		if (c.guessCooldownSeconds > 0 && max != 1) sb.append(" §8• §7one answer every ").append(c.guessCooldownSeconds).append(" s");
		sb.append(" §8• §7Time: ").append(minutesText(c.answerSeconds * 1000L));
		return sb.toString();
	}

	private static String noHelpLine(MegaConfig c, Prepared p) {
		if (p.race() != null) {
			String counts = switch (p.race().kind) {
				case "mine" -> "Only blocks you mine from now on count – never your inventory, placed blocks or Veinminer.";
				case "kill" -> "Only your kills from now on count – never your inventory.";
				default -> "Only what you do from now on counts – never your inventory.";
			};
			return "§c§lNo external help! §7No macros or auto-clickers. " + counts;
		}
		int max = p.question() == null ? -1 : maxGuesses(c, p.question());
		return "§c§lNo external help! §7No calculators, wikis, search engines or AI." + (max == 1 ? " §eOnly your FIRST answer counts." : "");
	}

	static String minutesText(long ms) {
		return ms % 60_000 == 0 ? ms / 60_000 + " min" : clockText(ms) + " min";
	}

	public void tick() {
		long now = clock.getAsLong();
		MegaConfig c = config.get();
		if (pendingExplanation != null && now >= explainAt) {
			List<Component> lines = pendingExplanation;
			pendingExplanation = null;
			for (Component line : lines) out.broadcast(Msg.legacy("§6§lSolution §8» ").append(line));
		}
		switch (phase) {
			case ANNOUNCE -> {
				if (now >= questionAt) {
					begin(now);
					return;
				}
				long left = questionAt - now;
				if (!reminded && left <= 10_000 && c.announceSeconds > 15) {
					reminded = true;
					out.broadcast(prefixed("§e10 seconds until the MEGA-CHALLENGE!"));
				}
				updateBar(now);
			}
			case QUESTION -> {
				if (now >= endsAt) {
					timeout(now);
					return;
				}
				dropChatGamesOff();
				MegaQuestion q = current.question();
				if (hintsShown < q.hints().size()) {
					long every = Math.max(10, c.hintSeconds) * 1000L;
					int due = (int) Math.min(q.hints().size(), (now - questionAt) / every);
					while (hintsShown < due) {
						out.broadcast(prefixed(Msg.legacy("§e§lHint §8» ").append(q.hints().get(hintsShown))));
						hintsShown++;
					}
				}
				updateBar(now);
			}
			case RACE -> {
				if (now >= endsAt) {
					timeout(now);
					return;
				}
				if (current.race().night && env.nightMillisLeft() <= 0) {
					// sunrise (also from sleeping): night kills no longer count
					timeout(now, "§7The sun has risen – nobody finished the night hunt in time.");
					return;
				}
				if (now - lastProgressAt >= 2000) {
					lastProgressAt = now;
					showProgress(now);
				}
				if (now - lastRaceLogAt >= 60_000) {
					lastRaceLogAt = now;
					logRaceProgress(now);
				}
				updateBar(now);
			}
			case IDLE -> schedule(now, c);
		}
	}

	private void logRaceProgress(long now) {
		RaceProbe probe = env.raceProbe();
		MegaConfig.Race race = current.race();
		List<String> parts = new ArrayList<>();
		List<UUID> order = new ArrayList<>(participants);
		order.sort((x, y) -> Long.compare(probe.progress(y), probe.progress(x)));
		for (UUID p : order) {
			long prog = probe.progress(p);
			if (prog <= 0) continue;
			parts.add(EconomyService.nameOrUnknown(p) + "=" + prog + "/" + race.amount + (probe.canWin(p, now) ? "" : "(inactive)"));
		}
		SmpEconomy.LOG.info("[SMPEco] MEGA race #{} {} after {}: {}", id, race.id, clockText(now - questionAt),
			parts.isEmpty() ? "nobody with progress yet" : String.join(", ", parts));
	}

	private void schedule(long now, MegaConfig c) {
		if (!c.enabled) return;
		if (nextAt < 0) nextAt = computeNextAt(now);
		if (now < nextAt) return;
		if (env.eligible().size() < Math.max(1, c.minPlayers)) return; // waits until someone is around
		if (env.chatGameRunning()) return; // waits until the running chat game is over
		boolean bonusRoll = c.bonusChance > 0 && c.bonusReward > 0 && random.nextDouble() < c.bonusChance;
		String error = start(null, null, true, bonusRoll);
		if (error != null) {
			SmpEconomy.LOG.warn("[SMPEco] MEGA-CHALLENGE could not start: {}", error);
			nextAt = now + 10 * 60_000L;
		}
	}

	public void raceTick() {
		if (phase != Phase.RACE || current == null || current.race() == null) return;
		long now = clock.getAsLong();
		if (now >= endsAt) return;
		RaceProbe probe = env.raceProbe();
		probe.sample(now);
		int amount = current.race().amount;
		UUID best = null;
		long bestProgress = -1;
		List<UUID> order = new ArrayList<>(participants);
		order.sort(UUID::compareTo);
		for (UUID p : order) {
			if (!env.chatGamesOn(p) || !probe.canWin(p, now)) continue;
			long prog = probe.progress(p);
			if (prog >= amount && prog > bestProgress) {
				best = p;
				bestProgress = prog;
			}
		}
		if (best != null) win(best, null, now);
	}

	private void showProgress(long now) {
		RaceProbe probe = env.raceProbe();
		MegaConfig.Race race = current.race();
		for (UUID p : participants) {
			if (!env.chatGamesOn(p)) continue;
			long prog = probe.progress(p);
			if (prog < 0) continue;
			boolean idle = prog >= race.amount && !probe.canWin(p, now);
			out.actionBar(p, Msg.legacy("§c§lMEGA RACE §8» §e" + Math.min(prog, race.amount) + "§7/" + race.amount + " §8· §f"
				+ clockText(endsAt - now) + (idle ? " §8· §eGoal reached – move to win!" : "")));
		}
	}

	private void timeout(long now) {
		timeout(now, "§7Time's up – nobody completed the MEGA-CHALLENGE.");
	}

	private void timeout(long now, String message) {
		Prepared p = current;
		long cid = id;
		if (p.race() != null) logRaceProgress(now);
		endRuntime();
		out.title(Msg.legacy("§6§lMEGA-CHALLENGE"), Msg.legacy("§7Nobody made it."), Sound.END);
		out.broadcast(prefixed(message));
		if (p.question() != null) revealSolution(p.question(), now);
		env.postponeChatGames(now + config.get().chatGamePauseMinutes * 60_000L);
		nextAt = computeNextAt(now);
		SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{} ended without a winner ({}/{}{})", cid, p.type().id, p.variant(),
			p.question() != null ? ", solution: " + p.question().solutionPlain() : "");
	}

	private void revealSolution(MegaQuestion q, long now) {
		out.broadcast(prefixed(Msg.legacy("§7Answer: §a").append(q.solution().copy())));
		if (!q.explanation().isEmpty()) {
			pendingExplanation = q.explanation();
			explainAt = now + 2000;
		}
	}

	/**
	 * {@code suppress} in the result means the message could reveal the solution and must not appear publicly.
	 * {@link MegaQuestion#read} decides which messages those are without looking at the solution; reply and
	 * feedback to non-participants also never depend on whether it would be correct (no oracle).
	 */
	public GuessResult guess(UUID player, String name, String text) {
		if (phase != Phase.QUESTION || current == null || current.question() == null) return GuessResult.PASS;
		MegaQuestion q = current.question();
		// chat games off: doesn't see the question, doesn't play; their messages stay normal chat regardless of
		// content, so again no oracle. A participant drops out for this question.
		if (!env.chatGamesOn(player)) {
			if (participants.remove(player)) {
				dropped.add(player);
				out.send(player, prefixed("§7You turned chat games off – you're no longer taking part in this MEGA-CHALLENGE."));
			}
			return GuessResult.PASS;
		}
		MegaQuestion.Read read = q.read(text);
		if (!read.hidden()) return GuessResult.PASS;
		// from here on the message stays private, right or wrong
		long now = clock.getAsLong();
		MegaConfig c = config.get();
		if (now >= endsAt) {
			out.send(player, prefixed("§cToo late – time is up. §8(Your message stays private.)"));
			return GuessResult.of(Outcome.LATE);
		}
		if (!participants.contains(player)) {
			notParticipant(player, now);
			return GuessResult.of(Outcome.NOT_PARTICIPANT);
		}
		if (read.kind() == MegaQuestion.ReadKind.UNCLEAR) {
			out.send(player, prefixed(q.numeric()
				? "§7Not counted – type only " + (q.digitSequence() ? "the digits (e.g. §f531§7 or §f5 3 1§7)" : "the number (e.g. §f54§7 or §f-7§7)")
					+ ". §8Messages with digits stay private during the question."
				: "§7Not counted – type only the term, spelled exactly (upper/lower case doesn't matter). §8Messages that could be answers"
					+ " stay private during the question."));
			return GuessResult.of(Outcome.UNCLEAR);
		}
		Guesses g = guesses.computeIfAbsent(player, k -> new Guesses());
		long cooldown = c.guessCooldownSeconds * 1000L;
		if (g.lastAt > 0 && now - g.lastAt < cooldown) {
			out.send(player, prefixed("§cOnly one answer every " + c.guessCooldownSeconds + " seconds §7(" + secondsUp(g.lastAt + cooldown - now) + " s left)."));
			return GuessResult.of(Outcome.TOO_FAST);
		}
		int maxGuesses = maxGuesses(c, q);
		if (maxGuesses > 0 && g.count >= maxGuesses) {
			// only the first attempt per player and question counts; further ones get a private hint
			out.send(player, prefixed(maxGuesses == 1
				? "§7Only your first answer counts – you have already answered. §8(Ignored and kept private – wait for the solution.)"
				: "§cNo attempts left – wait for the solution."));
			return GuessResult.of(Outcome.NO_TRIES);
		}
		g.lastAt = now;
		g.count++;
		int left = maxGuesses > 0 ? maxGuesses - g.count : -1;
		String leftText = left > 0 ? " §7(" + left + (left == 1 ? " attempt left)" : " attempts left)")
			: left == 0 ? (maxGuesses == 1 ? " §7(only your first answer counts)" : " §7(no attempts left)") : "";
		if (now < answersOpenAt) {
			// same feedback for right and wrong; the attempt is used up (bots firing at a fixed time)
			out.send(player, prefixed("§cToo early – answers only count after the secret wait. §7This attempt is used up." + leftText));
			return GuessResult.of(Outcome.TOO_EARLY);
		}
		if (!q.accepted().contains(read.value())) {
			MutableComponent msg = prefixed("§7Your answer: ").append(Component.literal(text.length() > 48 ? text.substring(0, 48) + "…" : text))
				.append(Msg.legacy(" §8» §c✘ wrong" + leftText));
			out.send(player, msg);
			return GuessResult.of(Outcome.WRONG);
		}
		return win(player, name, now);
	}

	private void notParticipant(UUID player, long now) {
		Long last = noticeAt.get(player);
		if (last != null && now - last < 10_000) return;
		noticeAt.put(player, now);
		String reason = excluded.get(player);
		String why = reason != null ? "You can't win this MEGA-CHALLENGE (" + reason + ")"
			: dropped.contains(player) ? "You turned chat games off during the question and are no longer taking part"
			: "You're not taking part in this MEGA-CHALLENGE – only players who were online at the start can win";
		out.send(player, prefixed("§c" + why + ". §8Messages that could contain an answer stay private until the end."));
	}

	/** Otherwise toggling would reset attempts and gaps. */
	private void dropChatGamesOff() {
		List<UUID> off = new ArrayList<>();
		for (UUID p : participants) {
			if (!env.chatGamesOn(p)) off.add(p);
		}
		for (UUID p : off) {
			participants.remove(p);
			dropped.add(p);
			out.send(p, prefixed("§7You turned chat games off – you're no longer taking part in this MEGA-CHALLENGE."));
		}
	}

	/** End the game, record and book the payout in the same tick, then announce. */
	private GuessResult win(UUID winner, String playerName, long now) {
		Data d = data.get();
		Prepared p = current;
		long cid = id;
		long started = questionAt;
		long msAfterOpen = p.question() != null ? Math.max(0, now - answersOpenAt) : -1;
		boolean wasBonus = bonus;
		long reward = this.reward;
		if (p.race() != null) logRaceProgress(now);
		endRuntime(); // end first: only the first winner gets paid
		if (isPaid(d, cid)) {
			SmpEconomy.LOG.warn("[SMPEco] MEGA-CHALLENGE #{} was already paid, no second payout", cid);
			return GuessResult.of(Outcome.NOT_PARTICIPANT);
		}
		MegaConfig c = config.get();
		String name = playerName != null && !playerName.isBlank() ? playerName : EconomyService.nameOrUnknown(winner);
		long paid = 0;
		long pending = 0;
		String payError = null;
		if (reward > 0) {
			if (c.rewardsFromTreasury) {
				TreasuryRewards.Payout tp = TreasuryRewards.pay(winner, reward);
				paid = tp.paid();
				pending = tp.pending();
			} else {
				EconomyService.Result r = EconomyService.deposit(winner, reward);
				if (r.ok()) paid = reward;
				else payError = r.message();
			}
		}
		boolean key = false;
		double chance = keyChanceOverride != null ? keyChanceOverride.getAsDouble() : c.epicKeyChance;
		if (chance > 0 && CrateKeys.enabled() && random.nextDouble() < chance) { // keys need the crates module
			CrateKeys.give(winner, CrateKeys.EPIC, 1);
			key = true;
		}
		Payout rec = new Payout();
		rec.id = cid;
		rec.winner = winner.toString();
		rec.name = name;
		rec.type = p.type().id;
		rec.variant = p.variant();
		rec.amount = paid;
		rec.pending = pending;
		rec.key = key;
		rec.at = now;
		rec.msAfterOpen = msAfterOpen;
		rec.bonus = wasBonus;
		d.payouts.add(rec);
		while (d.payouts.size() > MAX_PAYOUT_RECORDS) d.payouts.remove(0);
		markDirty.run();
		PlayerDataService.addCounter(winner, WINS_COUNTER, 1);

		long secs = Math.max(0, (now - started) / 1000);
		if (msAfterOpen >= 0 && msAfterOpen < 1500) {
			SmpEconomy.LOG.warn("[SMPEco] MEGA-CHALLENGE #{}: {} answered correctly {} ms after the (secret) unlock, please check (macro/bot?)", cid, name,
				msAfterOpen);
		}
		out.title(Msg.legacy(wasBonus ? "§d§lBONUS QUESTION" : "§6§lMEGA-CHALLENGE"), Msg.legacy("§e" + name + " §7wins " + Msg.moneyStr(reward) + "§7!"),
			Sound.WIN);
		out.broadcast(prefixed("§e§l" + name + " §7won the MEGA-CHALLENGE §8(" + clockText(secs * 1000) + ")§7! §a+" + Money.format(reward)
			+ (key ? " §8• §d+1 " + CrateKeys.label(CrateKeys.EPIC) + " Key" : "")));
		if (p.question() != null) {
			revealSolution(p.question(), now);
		} else if (p.race() != null) {
			out.broadcast(prefixed("§7Challenge: §f" + p.race().label + " §7– done after §f" + clockText(secs * 1000) + "§7."));
		}
		if (payError != null) out.send(winner, prefixed("§cYour prize couldn't be paid out: " + payError));
		if (pending > 0) out.send(winner, prefixed("§7The Treasury is out of budget right now – " + Msg.moneyStr(pending)
			+ " §7will be paid to you automatically once there's enough again (while you're online)."));
		env.postponeChatGames(now + c.chatGamePauseMinutes * 60_000L);
		nextAt = computeNextAt(now);
		SmpEconomy.LOG.info("[SMPEco] MEGA-CHALLENGE #{}{} won by {} after {} s{} ({}/{}{}), paid {}, pending {}, keys {}", cid,
			wasBonus ? " (BONUS)" : "", name, secs, msAfterOpen >= 0 ? " (" + msAfterOpen + " ms after unlock)" : "", p.type().id, p.variant(),
			p.question() != null ? ", solution: " + p.question().solutionPlain() : "", paid, pending, key);
		return new GuessResult(Outcome.WON, true, paid, pending, key);
	}

	static boolean isPaid(Data d, long challengeId) {
		for (Payout p : d.payouts) {
			if (p.id == challengeId) return true;
		}
		return false;
	}

	public String statusLine() {
		long now = clock.getAsLong();
		Data d = data.get();
		Payout last = d.payouts.isEmpty() ? null : d.payouts.get(d.payouts.size() - 1);
		StringBuilder sb = new StringBuilder("mega phase=").append(phase.id).append(" id=").append(phase == Phase.IDLE ? d.seq : id);
		if (current != null) {
			sb.append(" type=").append(current.type().id).append(" variant=").append(current.variant()).append(" bonus=").append(bonus)
				.append(" reward=").append(reward);
			if (phase == Phase.ANNOUNCE) sb.append(" startsIn=").append(Math.max(0, (questionAt - now) / 1000)).append("s");
			else sb.append(" endsIn=").append(Math.max(0, (endsAt - now) / 1000)).append("s").append(" participants=").append(participants.size());
		}
		MegaConfig c = config.get();
		sb.append(" enabled=").append(c.enabled).append(" interval=").append(c.intervalMinutes).append("m");
		sb.append(" nextIn=").append(nextAt < 0 ? "-" : Math.max(0, (nextAt - now) / 1000) + "s");
		sb.append(" lastRunAt=").append(d.lastRunAt);
		sb.append(" payouts=").append(d.payouts.size());
		if (last != null) {
			sb.append(" lastPaidId=").append(last.id).append(" lastWinner=").append(last.name).append(" lastAmount=").append(last.amount)
				.append(" lastBonus=").append(last.bonus);
		}
		return sb.toString();
	}

	/** RCON only. Null until the question is asked, so not during the announcement. */
	public String solutionLine() {
		if (phase != Phase.QUESTION || current == null || current.question() == null) return null;
		MegaQuestion q = current.question();
		long opensIn = Math.max(0, answersOpenAt - clock.getAsLong());
		return "mega solution=" + q.solutionPlain() + " accepted=" + String.join("|", q.accepted()) + " opensIn=" + opensIn + "ms";
	}

	public List<Component> playerStatus(UUID player) {
		long now = clock.getAsLong();
		MegaConfig c = config.get();
		List<Component> lines = new ArrayList<>();
		switch (phase) {
			case ANNOUNCE -> {
				lines.add(prefixed((bonus ? "§d§lBONUS QUESTION §8» " : "") + "§eThe MEGA-CHALLENGE starts in §f" + secondsUp(questionAt - now)
					+ " s§e! §7Category: §f" + current.type().label + " §8• §7Prize: " + Msg.moneyStr(reward)));
				lines.add(prefixed(noHelpLine(c, current)));
			}
			case QUESTION -> {
				MegaQuestion q = current.question();
				lines.add(prefixed((bonus ? "§d§lBONUS QUESTION §8» " : "") + "§e§l" + title(current) + " §8• §7left: §f" + clockText(endsAt - now)));
				for (Component line : q.lines()) lines.add(prefixed(line));
				for (int i = 0; i < hintsShown; i++) lines.add(prefixed(Msg.legacy("§e§lHint §8» ").append(q.hints().get(i))));
				if (!participants.contains(player)) lines.add(prefixed(notParticipantText(player)));
				else {
					Guesses g = guesses.get(player);
					int used = g == null ? 0 : g.count;
					int max = maxGuesses(c, q);
					if (max == 1) {
						lines.add(prefixed(used == 0 ? "§7Your answer: §fnone yet §8– only your first answer counts."
							: "§7You have answered §8– only your first answer counts, wait for the solution."));
					} else if (max > 0) {
						lines.add(prefixed("§7Your attempts: §f" + used + "§7/" + max));
					}
				}
			}
			case RACE -> {
				lines.add(prefixed("§c§lRACE §8» §f" + current.race().label + " §8• §7left: §f" + clockText(endsAt - now)));
				lines.add(prefixed(noHelpLine(c, current)));
				if (participants.contains(player)) {
					long prog = env.raceProbe().progress(player);
					lines.add(prefixed("§7Your progress: §e" + Math.max(0, Math.min(prog, current.race().amount)) + "§7/" + current.race().amount));
					if (prog >= current.race().amount && !env.raceProbe().canWin(player, now)) {
						lines.add(prefixed("§eGoal reached – move or look around to win §8(AFK doesn't count)§e."));
					}
				} else {
					lines.add(prefixed(notParticipantText(player)));
				}
			}
			case IDLE -> lines.add(prefixed(c.enabled && nextAt > 0
				? "§7The next MEGA-CHALLENGE (" + Msg.moneyStr(c.reward) + "§7) starts in about §f" + dev.smpeconomy.core.TimeUtil.formatDuration(Math.max(0, nextAt - now)) + "§7."
				: "§7No MEGA-CHALLENGE is scheduled right now."));
		}
		return lines;
	}

	private String notParticipantText(UUID player) {
		String reason = excluded.get(player);
		if (reason != null) return "§7You can't win this time: §f" + reason + "§7.";
		if (dropped.contains(player)) return "§7You turned chat games off – you're not taking part this time.";
		return "§7You weren't online at the start – you can't win this time.";
	}

	public static String title(Prepared p) {
		String v = p.variantLabel();
		return v == null || v.isBlank() || v.equalsIgnoreCase(p.type().label) ? p.type().label : p.type().label + " §8» §e§l" + v;
	}

	/** If all question weights are 0, math. */
	private Type pickQuestionType(MegaConfig c) {
		Map<String, Integer> w = new java.util.LinkedHashMap<>();
		List<String> ids = new ArrayList<>();
		for (Type t : Type.values()) {
			if (t == Type.RACE) continue;
			ids.add(t.id);
			w.put(t.id, Math.max(0, c.weights.getOrDefault(t.id, 0)));
		}
		String id = pickWeighted(w, ids, random);
		return id == null ? Type.MATH : Type.parse(id);
	}

	private Type pickType(MegaConfig c) {
		List<Type> pool = new ArrayList<>();
		List<Integer> w = new ArrayList<>();
		int total = 0;
		for (Type t : Type.values()) {
			int weight = Math.max(0, c.weights.getOrDefault(t.id, 0));
			if (t == Type.RACE && c.races.stream().noneMatch(r -> r.weight > 0)) weight = 0;
			if (weight <= 0) continue;
			pool.add(t);
			w.add(weight);
			total += weight;
		}
		if (total <= 0) return null;
		int roll = random.nextInt(total);
		for (int i = 0; i < pool.size(); i++) {
			roll -= w.get(i);
			if (roll < 0) return pool.get(i);
		}
		return pool.get(pool.size() - 1);
	}

	public static String pickWeighted(Map<String, Integer> weights, List<String> allowed, Random random) {
		List<String> pool = new ArrayList<>();
		List<Integer> w = new ArrayList<>();
		int total = 0;
		for (String k : allowed) {
			int weight = Math.max(0, weights == null ? 1 : weights.getOrDefault(k, 0));
			if (weight <= 0) continue;
			pool.add(k);
			w.add(weight);
			total += weight;
		}
		if (total <= 0) return null;
		int roll = random.nextInt(total);
		for (int i = 0; i < pool.size(); i++) {
			roll -= w.get(i);
			if (roll < 0) return pool.get(i);
		}
		return pool.get(pool.size() - 1);
	}

	private void saveActive() {
		Data d = data.get();
		ActiveInfo a = new ActiveInfo();
		a.id = id;
		a.type = current.type().id;
		a.variant = current.variant();
		a.phase = phase.id;
		a.startedAt = announcedAt;
		a.questionAt = questionAt;
		a.endsAt = endsAt;
		d.active = a;
		markDirty.run();
	}

	private void resetRuntime() {
		phase = Phase.IDLE;
		current = null;
		bonus = false;
		reward = 0;
		lastRaceLogAt = 0;
		participants.clear();
		excluded.clear();
		dropped.clear();
		noticeAt.clear();
		guesses.clear();
		answersOpenAt = 0;
		hintsShown = 0;
		reminded = false;
		lastProgressAt = 0;
		pendingExplanation = null;
	}

	private void endRuntime() {
		boolean race = phase == Phase.RACE;
		resetRuntime();
		Data d = data.get();
		if (d.active != null) {
			d.active = null;
			markDirty.run();
		}
		if (race) env.raceProbe().end();
		out.hideBar();
	}

	private void updateBar(long now) {
		String name = bonus ? "§d§lBONUS MEGA-CHALLENGE" : "§6§lMEGA-CHALLENGE";
		switch (phase) {
			case ANNOUNCE -> {
				long total = Math.max(1, questionAt - announcedAt);
				out.bar(Msg.legacy(name + " §7starts in §e" + secondsUp(questionAt - now) + "s §8· " + Msg.moneyStr(reward)),
					clamp((questionAt - now) / (float) total), phase);
			}
			case QUESTION -> out.bar(Msg.legacy(name + " §8· §e" + current.type().label + " §8· §f" + clockText(endsAt - now) + " §8· "
				+ Msg.moneyStr(reward)), clamp((endsAt - now) / (float) Math.max(1, endsAt - questionAt)), phase);
			case RACE -> out.bar(Msg.legacy("§c§lMEGA RACE §8· §e" + current.race().label + " §8· §f" + clockText(endsAt - now)),
				clamp((endsAt - now) / (float) Math.max(1, endsAt - questionAt)), phase);
			default -> out.hideBar();
		}
	}

	private static float clamp(float f) {
		return Math.max(0f, Math.min(1f, f));
	}

	static String clockText(long ms) {
		long s = Math.max(0, (ms + 999) / 1000);
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}

	private static long secondsUp(long ms) {
		return Math.max(1, (ms + 999) / 1000);
	}

	static MutableComponent prefixed(String text) {
		return Msg.legacy(PREFIX + text);
	}

	static MutableComponent prefixed(Component content) {
		return Msg.legacy(PREFIX).append(content);
	}
}
