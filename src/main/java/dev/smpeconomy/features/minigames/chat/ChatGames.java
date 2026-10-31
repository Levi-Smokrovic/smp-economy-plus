package dev.smpeconomy.features.minigames.chat;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.minigames.MinigamesConfig;
import dev.smpeconomy.features.minigames.TreasuryRewards;
import dev.smpeconomy.features.minigames.chat.ChatGame.Type;

/**
 * Scheduling, answers, riddle hints and timeout. Instantiable with its own clock, random and output for selftests.
 * Server thread only.
 * <p>All messages go through the {@link Announcer}, which live only reaches players with {@link Settings#CHATGAMES}
 * on. Answers from players with chat games off don't count.
 */
public final class ChatGames {
	public static final String WINS_COUNTER = "minigames.chatgame_wins";

	public interface Announcer {
		void broadcast(Component message);

		void send(UUID player, Component message);
	}

	public enum Outcome { NO_GAME, DISABLED, WRONG, WON }

	/**
	 * On {@code WON}: the amount paid now, the part the treasury pays later ({@link TreasuryRewards}), whether a
	 * key came, and any payout error.
	 */
	public record AnswerResult(Outcome outcome, long reward, long pending, boolean key, String payError) {
		static final AnswerResult NO_GAME = new AnswerResult(Outcome.NO_GAME, 0, 0, false, null);
		static final AnswerResult DISABLED = new AnswerResult(Outcome.DISABLED, 0, 0, false, null);
		static final AnswerResult WRONG = new AnswerResult(Outcome.WRONG, 0, 0, false, null);
	}

	private final Supplier<MinigamesConfig.ChatGames> config;
	private final Supplier<ChatGameContent> content;
	private final LongSupplier clock;
	private final Random random;
	private final Announcer announcer;
	private DoubleSupplier keyChanceOverride;
	private BooleanSupplier paused = () -> false;
	private ChatGame active;
	private long nextAt = -1;

	public ChatGames(Supplier<MinigamesConfig.ChatGames> config, Supplier<ChatGameContent> content, LongSupplier clock, Random random, Announcer announcer) {
		this.config = config;
		this.content = content;
		this.clock = clock;
		this.random = random;
		this.announcer = announcer;
	}

	public void setKeyChanceOverride(DoubleSupplier chance) {
		this.keyChanceOverride = chance;
	}

	public void setPause(BooleanSupplier paused) {
		this.paused = paused == null ? () -> false : paused;
	}

	public void postpone(long until) {
		if (nextAt < until) nextAt = until;
	}

	public Optional<ChatGame> active() {
		return Optional.ofNullable(active);
	}

	public long nextAt() {
		return nextAt;
	}

	public ChatGame start(Type type) {
		if (active != null) stop("§7The running chat game was cancelled.");
		MinigamesConfig.ChatGames cfg = config.get();
		ChatGameContent c = content.get();
		Type t = type != null ? type : ChatGameFactory.pickType(cfg.weights, c, random);
		long now = clock.getAsLong();
		ChatGame game = ChatGameFactory.create(t, c, cfg, random, now);
		active = game;
		double global = globalMultiplier();
		long shownReward = scale(game.baseReward(), global);
		announcer.broadcast(Msg.prefixed("§e§l" + t.label + " §8» " + game.question()));
		announcer.broadcast(Msg.prefixed("§7Type your answer in chat! Prize: " + Msg.moneyStr(shownReward)
			+ (global > 1.0001 ? " §8(" + factor(global) + ")" : "") + " §8• §7Time: §f" + cfg.timeoutSeconds + "s"));
		return game;
	}

	public void stop(String message) {
		if (active == null) return;
		active = null;
		if (message != null) announcer.broadcast(Msg.prefixed(message));
	}

	public AnswerResult answer(UUID player, String name, String text) {
		ChatGame game = active;
		if (game == null) return AnswerResult.NO_GAME;
		if (!Settings.get(player, Settings.CHATGAMES)) return AnswerResult.DISABLED;
		if (clock.getAsLong() >= game.endsAt()) return AnswerResult.NO_GAME; // the timeout runs on the next tick
		if (!game.isCorrect(text)) return AnswerResult.WRONG;
		active = null; // end first: only the first winner gets paid

		double mult = Hooks.chatGameMultiplier(player);
		long reward = scale(game.baseReward(), mult);
		long paid = 0;
		long pending = 0;
		String payError = null;
		if (reward > 0) {
			if (config.get().rewardsFromTreasury) {
				// capped from the treasury; what can't be paid now is paid later (never lost, never newly created)
				TreasuryRewards.Payout p = TreasuryRewards.pay(player, reward);
				paid = p.paid();
				pending = p.pending();
			} else {
				EconomyService.Result r = EconomyService.deposit(player, reward);
				if (r.ok()) paid = reward;
				else payError = r.message();
			}
		}
		boolean key = false;
		double chance = keyChanceOverride != null ? keyChanceOverride.getAsDouble() : config.get().keyChance;
		if (chance > 0 && CrateKeys.enabled() && random.nextDouble() < chance) { // keys need the crates module
			CrateKeys.give(player, CrateKeys.COMMON, 1);
			key = true;
		}
		PlayerDataService.addCounter(player, WINS_COUNTER, 1);

		long seconds = Math.max(0, (clock.getAsLong() - game.startedAt()) / 1000);
		StringBuilder sb = new StringBuilder("§e" + name + " §7won §8(" + seconds + "s)§7! Answer: §f" + game.solution());
		if (payError == null && reward > 0) sb.append(" §8• §a+").append(Money.format(reward));
		if (Math.abs(mult - 1.0) > 1e-4) sb.append(" §8(").append(factorLabel(player, mult)).append(")");
		if (key) sb.append(" §8• §d+1 ").append(CrateKeys.label(CrateKeys.COMMON)).append(" Key");
		announcer.broadcast(Msg.prefixed(sb.toString()));
		if (payError != null) announcer.send(player, Msg.prefixed("§cYour prize couldn't be paid out: " + payError));
		if (pending > 0) announcer.send(player, Msg.prefixed("§7The Treasury is out of budget right now – " + Msg.moneyStr(pending)
			+ " §7will be paid to you automatically once there's enough again (while you're online)."));
		return new AnswerResult(Outcome.WON, paid, pending, key, payError);
	}

	public void tick(int eligiblePlayers) {
		long now = clock.getAsLong();
		MinigamesConfig.ChatGames cfg = config.get();
		ChatGame game = active;
		if (game != null) {
			if (now >= game.endsAt()) {
				active = null;
				announcer.broadcast(Msg.prefixed("§7Time's up – nobody got it. Answer: §f" + game.solution()));
				return;
			}
			if (game.type() == Type.RIDDLE && game.hintsShown() < game.hints().size()) {
				long every = Math.max(5, cfg.riddleHintSeconds) * 1000L;
				int due = (int) Math.min(game.hints().size(), 1 + (now - game.startedAt()) / every);
				while (game.hintsShown() < due) {
					int idx = game.hintsShown();
					game.setHintsShown(idx + 1);
					announcer.broadcast(Msg.prefixed("§e§lHint " + (idx + 1) + " §8» §f" + game.hints().get(idx)));
				}
			}
			return;
		}
		if (!cfg.enabled) return;
		if (paused.getAsBoolean()) return; // the MEGA-CHALLENGE postpones the next game when it ends
		if (nextAt < 0) {
			scheduleNext(now);
			return;
		}
		if (now < nextAt) return;
		if (eligiblePlayers >= Math.max(1, cfg.minPlayers)) {
			try {
				start(null);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Failed to start chat game", e);
			}
		}
		scheduleNext(now);
	}

	/** Global chat game boosts such as the lucky hour shorten the gap. */
	public void scheduleNext(long now) {
		MinigamesConfig.ChatGames cfg = config.get();
		long min = cfg.minIntervalMinutes * 60_000L;
		long max = Math.max(min, cfg.maxIntervalMinutes * 60_000L);
		long interval = min + (max > min ? (long) (random.nextDouble() * (max - min)) : 0);
		double freq = Math.max(1.0, Math.min(3.0, globalMultiplier()));
		nextAt = now + Math.max(30_000L, (long) (interval / freq));
	}

	/** Player-independent boosts: multiplier for the null UUID. */
	public static double globalMultiplier() {
		return Hooks.chatGameMultiplier(Util.NIL_UUID);
	}

	public static long scale(long base, double factor) {
		if (base <= 0 || !(factor > 0)) return 0;
		double v = base * factor;
		if (v >= Money.MAX) return Money.MAX;
		return Math.max(1, Math.round(v));
	}

	private static String factor(double f) {
		return "×" + (Math.abs(f - Math.rint(f)) < 1e-6 ? Long.toString(Math.round(f)) : String.format(java.util.Locale.ROOT, "%.2f", f));
	}

	private static String factorLabel(UUID player, double mult) {
		List<Hooks.Part> parts = Hooks.chatGameParts(player);
		String names = parts.isEmpty() ? "" : " " + String.join(", ", parts.stream().map(Hooks.Part::label).toList());
		return factor(mult) + names;
	}
}
