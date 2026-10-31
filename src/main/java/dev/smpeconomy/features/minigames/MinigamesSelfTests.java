package dev.smpeconomy.features.minigames;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.minigames.chat.Answers;
import dev.smpeconomy.features.minigames.chat.ChatGame;
import dev.smpeconomy.features.minigames.chat.ChatGameContent;
import dev.smpeconomy.features.minigames.chat.ChatGameFactory;
import dev.smpeconomy.features.minigames.chat.ChatGames;
import dev.smpeconomy.features.minigames.coinflip.Coinflips;
import dev.smpeconomy.features.minigames.treasure.TreasureHunt;

/**
 * All tests use their own instances (storage, clock, random, output), so live coinflips, hunts and chat games stay
 * untouched.
 */
final class MinigamesSelfTests {
	private static final String F = MinigamesFeature.ID;

	private MinigamesSelfTests() {}

	static void register() {
		SelfTests.register(F, "answer-normalization", ctx -> answers());
		SelfTests.register(F, "content-and-unscramble", ctx -> contentAndScramble());
		SelfTests.register(F, "math-tasks", ctx -> mathTasks());
		SelfTests.register(F, "chatgame-first-winner-only", MinigamesSelfTests::onlyFirstWinner);
		SelfTests.register(F, "chatgame-setting-off", MinigamesSelfTests::settingOff);
		SelfTests.register(F, "chatgame-timeout-and-hints", MinigamesSelfTests::timeoutAndHints);
		SelfTests.register(F, "chatgame-scheduling", MinigamesSelfTests::scheduling);
		SelfTests.register(F, "chatgame-multiplier-key-treasury", MinigamesSelfTests::multiplierKeyTreasury);
		SelfTests.register(F, "treasury-win-backpay", MinigamesSelfTests::treasuryRewardsPending);
		SelfTests.register(F, "coinflip-conservation-with-fee", MinigamesSelfTests::coinflipConservation);
		SelfTests.register(F, "coinflip-edge-cases", MinigamesSelfTests::coinflipEdgeCases);
		SelfTests.register(F, "coinflip-refund-on-stop", MinigamesSelfTests::coinflipRefundOnStop);
		SelfTests.register(F, "coinflip-persistence", ctx -> coinflipPersistence());
		SelfTests.register(F, "coinflip-separate-rng", MinigamesSelfTests::coinflipRandomSeparated);
		SelfTests.register(F, "coinflip-announce-threshold-cooldown", MinigamesSelfTests::coinflipAnnouncementGate);
		SelfTests.register(F, "treasure-hints-and-reward", ctx -> treasureHints());
		SelfTests.register(F, "treasure-safe-position", ctx -> treasureSafeSpot());
		SelfTests.register(F, "treasure-not-on-builds", MinigamesSelfTests::treasureNoBuilds);
		SelfTests.register(F, "treasure-find-exactly-once", MinigamesSelfTests::treasureClaimOnce);
		SelfTests.register(F, "treasure-timeout-and-cleanup", MinigamesSelfTests::treasureTimeoutCleanup);
		SelfTests.register(F, "treasure-range", MinigamesSelfTests::treasureReach);
		SelfTests.register(F, "treasure-treasury-capped", MinigamesSelfTests::treasureFromTreasury);
	}

	private static final class Recorder implements ChatGames.Announcer, TreasureHunt.Announcer {
		final List<String> lines = new ArrayList<>();

		@Override
		public void broadcast(Component message) {
			lines.add(message.getString());
		}

		@Override
		public void send(UUID player, Component message) {
			lines.add("@" + message.getString());
		}

		@Override
		public void startTitle() {
			lines.add("TITLE");
		}

		@Override
		public void proximity(TreasureHunt hunt, TreasureHunt.Active active) {}

		boolean any(String part) {
			return lines.stream().anyMatch(l -> l.contains(part));
		}

		long count(String part) {
			return lines.stream().filter(l -> l.contains(part)).count();
		}
	}

	private static ChatGameContent riddleContent() {
		ChatGameContent c = new ChatGameContent();
		c.words = new ArrayList<>(List.of("DIAMOND", "PUMPKIN"));
		c.phrases = new ArrayList<>(List.of("Creepers go boom"));
		ChatGameContent.Riddle r = new ChatGameContent.Riddle();
		r.answers = new ArrayList<>(List.of("Golden Apple", "Gold Apple", "GApple"));
		r.hints = new ArrayList<>(List.of("Hint-One", "Hint-Two", "Hint-Three"));
		c.riddles = new ArrayList<>(List.of(r));
		return c;
	}

	private static ChatGames chatGames(MinigamesConfig cfg, ChatGameContent content, long[] now, Recorder rec) {
		return new ChatGames(() -> cfg.chatGames, () -> content, () -> now[0], new Random(42), rec);
	}

	private static void answers() {
		eq("diamond", Answers.normalize("  Diamond "), "trim + lower case");
		eq("golden apple", Answers.normalize("Golden   Apple!"), "spaces/punctuation");
		eq("smp k7qx2", Answers.normalize("smp-K7QX2"), "code with hyphen");
		isTrue(Answers.matches("First to type wins", "first TO type wins."), "case + period ignored");
		isFalse(Answers.matches("Diamond", "Diamonds"), "other word");
		isFalse(Answers.matches("", ""), "empty solution never matches");
		isFalse(Answers.matches("19", "1 9"), "separated digits are not a number");
		eq("", Answers.normalize(null), "null");
	}

	private static void contentAndScramble() {
		ChatGameContent c = MinigamesFeature.CONTENT.get();
		isTrue(c.words.size() >= ChatGameContent.MIN_WORDS, "at least 80 words (" + c.words.size() + ")");
		isTrue(c.phrases.size() >= 10, "fast-type phrases (" + c.phrases.size() + ")");
		isTrue(c.riddles.size() >= 20, "riddles (" + c.riddles.size() + ")");
		for (ChatGameContent.Riddle r : c.riddles) {
			isTrue(!r.answers.isEmpty() && !r.hints.isEmpty(), "riddle with answer and hint: " + r.answers);
		}
		MinigamesConfig cfg = new MinigamesConfig();
		Random random = new Random(7);
		for (String w : c.words) {
			String word = w.toUpperCase(java.util.Locale.ROOT);
			for (int i = 0; i < 3; i++) {
				String s = ChatGameFactory.scramble(word, random);
				isFalse(s.equals(word), "scrambled looks different: " + word);
				eq(sorted(word), sorted(s), "same letters: " + word);
			}
		}
		for (int i = 0; i < 50; i++) {
			ChatGame g = ChatGameFactory.create(ChatGame.Type.UNSCRAMBLE, c, cfg.chatGames, random, 0);
			isTrue(g.isCorrect(g.solution()), "solution accepted: " + g.solution());
			isTrue(g.isCorrect(g.solution().toLowerCase(java.util.Locale.ROOT)), "lower case: " + g.solution());
			ChatGame t = ChatGameFactory.create(ChatGame.Type.FAST_TYPE, c, cfg.chatGames, random, 0);
			isTrue(t.isCorrect(t.solution()), "fast type: " + t.solution());
			ChatGame r = ChatGameFactory.create(ChatGame.Type.RIDDLE, c, cfg.chatGames, random, 0);
			isTrue(r.isCorrect(r.solution()), "riddle solution: " + r.solution());
			isFalse(r.isCorrect("xyz"), "wrong riddle answer");
		}
		ChatGameContent bad = new ChatGameContent();
		bad.words = new ArrayList<>(List.of("AB", "DIAMOND"));
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ChatGameContent.validate(bad), "too few words rejected");
		eq(ChatGame.Type.MATH, ChatGame.Type.parse("MATH"), "type by id");
		eq(null, ChatGame.Type.parse("nonsense"), "unknown type");
	}

	private static String sorted(String s) {
		char[] c = s.toCharArray();
		java.util.Arrays.sort(c);
		return new String(c);
	}

	private static void mathTasks() {
		Random random = new Random(1234);
		for (int i = 0; i < 1000; i++) {
			ChatGameFactory.MathTask t = ChatGameFactory.math(random);
			long value = new Expr(t.expression()).parse();
			eq(value, t.answer(), "result of " + t.expression());
			isTrue(t.answer() > 0, "positive result: " + t.expression());
		}
		ChatGame g = ChatGameFactory.create(ChatGame.Type.MATH, null, new MinigamesConfig().chatGames, random, 0);
		isTrue(g.isCorrect(" " + g.solution() + " "), "number with spaces");
		isFalse(g.isCorrect(g.solution() + "0"), "wrong number");
	}

	/** Small parser for + - × and parentheses, to check tasks independently of the generator. */
	private static final class Expr {
		private final String s;
		private int i;

		Expr(String s) {
			this.s = s.replace(" ", "");
		}

		long parse() {
			long v = sum();
			if (i != s.length()) throw new AssertionError("leftover in expression: " + s.substring(i));
			return v;
		}

		private long sum() {
			long v = product();
			while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
				char op = s.charAt(i++);
				long r = product();
				v = op == '+' ? v + r : v - r;
			}
			return v;
		}

		private long product() {
			long v = atom();
			while (i < s.length() && s.charAt(i) == '×') {
				i++;
				v *= atom();
			}
			return v;
		}

		private long atom() {
			if (s.charAt(i) == '(') {
				i++;
				long v = sum();
				if (s.charAt(i++) != ')') throw new AssertionError("missing parenthesis: " + s);
				return v;
			}
			int start = i;
			while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
			return Long.parseLong(s.substring(start, i));
		}
	}

	private static void onlyFirstWinner(TestContext ctx) {
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.chatGames.rewardMath = 500;
		cfg.chatGames.keyChance = 0;
		long[] now = {1_000_000};
		Recorder rec = new Recorder();
		ChatGames games = chatGames(cfg, riddleContent(), now, rec);
		ChatGame g = games.start(ChatGame.Type.MATH);
		isTrue(rec.any("Solve"), "question announced");
		eq(ChatGames.Outcome.WRONG, games.answer(b.getUUID(), "MiniB", "no").outcome(), "wrong answer");
		isTrue(games.active().isPresent(), "game continues after wrong answer");
		ChatGames.AnswerResult r1 = games.answer(a.getUUID(), "MiniA", g.solution());
		eq(ChatGames.Outcome.WON, r1.outcome(), "first correct answer wins");
		long expected = ChatGames.scale(500, Hooks.chatGameMultiplier(a.getUUID()));
		eq(expected, r1.reward(), "prize = base × multiplier");
		ChatGames.AnswerResult r2 = games.answer(b.getUUID(), "MiniB", g.solution());
		eq(ChatGames.Outcome.NO_GAME, r2.outcome(), "second correct answer gets nothing");
		eq(expected, EconomyService.balance(a.getUUID()), "winner paid");
		eq(0L, EconomyService.balance(b.getUUID()), "second not paid");
		eq(1L, PlayerDataService.getCounter(a.getUUID(), ChatGames.WINS_COUNTER), "win counter");
		eq(1L, rec.count(" won "), "exactly one winner message");
		ctx.detail("prize " + Money.format(expected) + ", second correct answer empty");
	}

	private static void settingOff(TestContext ctx) {
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);
		Settings.set(a.getUUID(), Settings.CHATGAMES, false);
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.chatGames.keyChance = 0;
		long[] now = {5_000_000};
		Recorder rec = new Recorder();
		ChatGames games = chatGames(cfg, riddleContent(), now, rec);
		ChatGame g = games.start(ChatGame.Type.FAST_TYPE);
		eq(ChatGames.Outcome.DISABLED, games.answer(a.getUUID(), "MiniA", g.solution()).outcome(), "chat games off: answer does not count");
		isTrue(games.active().isPresent(), "game continues");
		eq(0L, EconomyService.balance(a.getUUID()), "no money with chat games off");
		eq(ChatGames.Outcome.WON, games.answer(b.getUUID(), "MiniB", g.solution().toUpperCase(java.util.Locale.ROOT)).outcome(), "other player wins (case ignored)");
		Settings.set(a.getUUID(), Settings.CHATGAMES, true);
		ChatGame g2 = games.start(ChatGame.Type.FAST_TYPE);
		eq(ChatGames.Outcome.WON, games.answer(a.getUUID(), "MiniA", g2.solution()).outcome(), "on again: counts");
	}

	private static void timeoutAndHints(TestContext ctx) {
		FakePlayer a = ctx.player("MiniA");
		EconomyService.set(a.getUUID(), 0);
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.chatGames.timeoutSeconds = 60;
		cfg.chatGames.riddleHintSeconds = 20;
		cfg.chatGames.keyChance = 0;
		long start = 10_000_000;
		long[] now = {start};
		Recorder rec = new Recorder();
		ChatGames games = chatGames(cfg, riddleContent(), now, rec);
		ChatGame g = games.start(ChatGame.Type.RIDDLE);
		isTrue(rec.any("Hint-One"), "first hint immediately");
		eq(1, g.hintsShown(), "1 hint");
		now[0] = start + 19_999;
		games.tick(1);
		eq(1, g.hintsShown(), "still 1 hint after 19.9 s");
		now[0] = start + 20_000;
		games.tick(1);
		eq(2, g.hintsShown(), "2 hints after 20 s");
		isTrue(rec.any("Hint-Two"), "second hint announced");
		now[0] = start + 45_000;
		games.tick(1);
		eq(3, g.hintsShown(), "all 3 hints after 45 s");
		now[0] = start + 59_999;
		games.tick(1);
		isTrue(games.active().isPresent(), "still running after 59.9 s");
		eq(ChatGames.Outcome.WRONG, games.answer(a.getUUID(), "MiniA", "Diamond").outcome(), "wrong just before the end");
		now[0] = start + 60_000;
		eq(ChatGames.Outcome.NO_GAME, games.answer(a.getUUID(), "MiniA", "Golden Apple").outcome(), "no answer counts after expiry");
		games.tick(1);
		isTrue(games.active().isEmpty(), "timeout ends the game");
		isTrue(rec.any("Time's up") && rec.any("Golden Apple"), "timeout message with solution");
		eq(0L, EconomyService.balance(a.getUUID()), "no prize after timeout");
		ChatGame g2 = games.start(ChatGame.Type.RIDDLE);
		eq(ChatGames.Outcome.WON, games.answer(a.getUUID(), "MiniA", "gold apple").outcome(), "alternative answer");
		isTrue(g2.accepted().contains("golden apple"), "English answer accepted");
	}

	private static void scheduling(TestContext ctx) {
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.chatGames.minIntervalMinutes = 10;
		cfg.chatGames.maxIntervalMinutes = 20;
		long[] now = {50_000_000};
		Recorder rec = new Recorder();
		ChatGames games = chatGames(cfg, riddleContent(), now, rec);
		games.tick(1);
		long next = games.nextAt();
		double freq = Math.max(1.0, Math.min(3.0, ChatGames.globalMultiplier()));
		isTrue(next >= now[0] + (long) (10 * 60_000 / freq) - 1 && next <= now[0] + 20 * 60_000, "next game in 10-20 min (" + (next - now[0]) / 1000 + " s)");
		isTrue(games.active().isEmpty(), "no game immediately");
		now[0] = next - 1;
		games.tick(1);
		isTrue(games.active().isEmpty(), "no game before the time");
		now[0] = next;
		games.tick(0);
		isTrue(games.active().isEmpty(), "no game without players");
		isTrue(games.nextAt() > next, "rescheduled without players");
		now[0] = games.nextAt();
		games.tick(1);
		isTrue(games.active().isPresent(), "game starts with a player");
		now[0] = games.active().get().endsAt();
		games.tick(1);
		isTrue(games.active().isEmpty(), "Timeout");
		cfg.chatGames.enabled = false;
		now[0] = games.nextAt() + 1;
		games.tick(5);
		isTrue(games.active().isEmpty(), "disabled: no game");
	}

	private static void multiplierKeyTreasury(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("MiniA");
		EconomyService.set(a.getUUID(), 0);
		double before = Hooks.chatGameMultiplier(a.getUUID());
		Hooks.Registration reg = Hooks.addChatGameMultiplier("minigames.selftest", "Selftest", p -> p.equals(a.getUUID()) ? 2.0 : 1.0);
		ctx.cleanup(reg::remove);
		double mult = Hooks.chatGameMultiplier(a.getUUID());
		isTrue(Math.abs(mult - before * 2.0) < 1e-9, "multiplier applies (" + before + " → " + mult + ")");
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.chatGames.rewardFastType = 1_000;
		long[] now = {70_000_000};
		Recorder rec = new Recorder();
		ChatGames games = chatGames(cfg, riddleContent(), now, rec);
		games.setKeyChanceOverride(() -> 1.0);
		long keys0 = CrateKeys.count(a.getUUID(), CrateKeys.COMMON);
		ChatGame g = games.start(ChatGame.Type.FAST_TYPE);
		ChatGames.AnswerResult r = games.answer(a.getUUID(), "MiniA", g.solution());
		eq(ChatGames.scale(1_000, mult), r.reward(), "prize × multiplier");
		eq(ChatGames.scale(1_000, mult), EconomyService.balance(a.getUUID()), "balance");
		isTrue(r.key(), "key at chance 1.0");
		eq(keys0 + 1, CrateKeys.count(a.getUUID(), CrateKeys.COMMON), "common key via CrateKeys");
		isTrue(rec.any("Selftest"), "multiplier name in the win message");
		games.setKeyChanceOverride(() -> 0.0);
		g = games.start(ChatGame.Type.FAST_TYPE);
		isFalse(games.answer(a.getUUID(), "MiniA", g.solution()).key(), "no key at chance 0");
		eq(keys0 + 1, CrateKeys.count(a.getUUID(), CrateKeys.COMMON), "key count unchanged");

		cfg.chatGames.rewardsFromTreasury = true;
		cfg.chatGames.rewardFastType = 1;
		long reward = ChatGames.scale(1, mult);
		long treasury = Treasury.balance();
		long bal = EconomyService.balance(a.getUUID());
		boolean canPay = Treasury.canSpend(reward);
		g = games.start(ChatGame.Type.FAST_TYPE);
		ChatGames.AnswerResult t = games.answer(a.getUUID(), "MiniA", g.solution());
		isTrue(t.payError() == null, "no error");
		eq(reward, t.reward() + t.pending(), "immediate + pending = full prize");
		eq(canPay ? 0L : reward, t.pending(), canPay ? "treasury with budget pays immediately" : "treasury at limit: pending");
		eq(treasury - t.reward(), Treasury.balance(), "treasury pays only the immediate part");
		eq(bal + t.reward(), EconomyService.balance(a.getUUID()), "player gets the immediate part");
		eq(t.pending(), TreasuryRewards.pending(a.getUUID()), "pending part recorded");

		long allowance = Treasury.allowance();
		cfg.chatGames.rewardFastType = Math.min(Money.MAX / 4, (long) Math.ceil((allowance + 1) / mult) + 1);
		long big = ChatGames.scale(cfg.chatGames.rewardFastType, mult);
		isTrue(big > allowance, "prize above the budget (" + big + " > " + allowance + ")");
		long pending0 = TreasuryRewards.pending(a.getUUID());
		treasury = Treasury.balance();
		bal = EconomyService.balance(a.getUUID());
		g = games.start(ChatGame.Type.FAST_TYPE);
		ChatGames.AnswerResult over = games.answer(a.getUUID(), "MiniA", g.solution());
		eq(ChatGames.Outcome.WON, over.outcome(), "won");
		eq(0L, over.reward(), "nothing immediately");
		eq(big, over.pending(), "whole prize pending");
		eq(pending0 + big, TreasuryRewards.pending(a.getUUID()), "pending counter");
		eq(treasury, Treasury.balance(), "treasury unchanged");
		eq(bal, EconomyService.balance(a.getUUID()), "account unchanged");
		isTrue(rec.any("paid to you automatically"), "winner hears about the later payment");
	}

	private static void treasuryRewardsPending(TestContext ctx) {
		ctx.preserveTreasury();
		Runnable resetTreasury = Treasury.snapshot();
		FakePlayer a = ctx.player("MiniA");
		UUID id = a.getUUID();
		EconomyService.set(id, 0);
		eq(0L, TreasuryRewards.pending(id), "nothing pending at first");
		eq(0L, TreasuryRewards.settle(id), "nothing pending: nothing paid");
		long allowance = Treasury.allowance();
		long t0 = Treasury.balance();
		long amount = allowance + 1;
		TreasuryRewards.Payout p = TreasuryRewards.pay(id, amount);
		eq(0L, p.paid(), "above the budget: nothing immediately");
		eq(amount, p.pending(), "whole amount pending");
		eq(amount, TreasuryRewards.pending(id), "counter");
		eq(t0, Treasury.balance(), "treasury unchanged");
		eq(0L, EconomyService.balance(id), "account unchanged");
		long paid1 = TreasuryRewards.settle(id);
		eq(allowance, paid1, "later payment at most the budget");
		eq(amount - paid1, TreasuryRewards.pending(id), "rest stays pending");
		eq(t0 - paid1, Treasury.balance(), "treasury pays the later payment");
		eq(paid1, EconomyService.balance(id), "player gets the later payment");
		eq(0L, TreasuryRewards.settle(id), "budget used up: no further payment");
		eq(1L, TreasuryRewards.pending(id), "rest recorded");
		resetTreasury.run();
		if (Treasury.allowance() > 0) {
			long t1 = Treasury.balance();
			eq(1L, TreasuryRewards.settle(id), "rest paid");
			eq(0L, TreasuryRewards.pending(id), "nothing pending anymore");
			eq(t1 - 1, Treasury.balance(), "treasury pays the rest");
			eq(paid1 + 1, EconomyService.balance(id), "player has the full amount");
			ctx.detail("budget " + Money.format(allowance) + ": pending, partial payment, rest, sum preserved");
		} else {
			ctx.detail("treasury without budget: prize fully pending, no payment");
		}
	}

	private static Coinflips coinflips(MinigamesConfig cfg, Coinflips.Data data, long seed) {
		return new Coinflips(() -> data, () -> {}, () -> cfg.coinflip, new Random(seed), () -> 1_000_000L);
	}

	private static long total(UUID a, UUID b, Coinflips cf) {
		return EconomyService.balance(a) + EconomyService.balance(b) + Treasury.balance() + cf.escrow();
	}

	private static void coinflipConservation(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.coinflip.minBet = 100;
		cfg.coinflip.feePercent = 2.0;
		Coinflips cf = coinflips(cfg, new Coinflips.Data(), 3);
		for (boolean creatorWins : new boolean[] {true, false}) {
			EconomyService.set(a.getUUID(), 100_000);
			EconomyService.set(b.getUUID(), 100_000);
			long t0 = total(a.getUUID(), b.getUUID(), cf);
			long tr0 = Treasury.balance();
			Coinflips.Result r = cf.create(a.getUUID(), "MiniA", 10_000);
			isTrue(r.ok(), "created: " + r.error());
			eq(90_000L, EconomyService.balance(a.getUUID()), "stake debited immediately");
			eq(10_000L, cf.escrow(), "Escrow");
			eq(t0, total(a.getUUID(), b.getUUID(), cf), "sum unchanged after creating");
			Coinflips.Outcome o = cf.accept(b.getUUID(), "MiniB", r.flip().id, 10_000, creatorWins);
			isTrue(o.ok(), "accepted: " + o.error());
			eq(20_000L, o.pot(), "Pot");
			eq(400L, o.fee(), "2 % fee");
			eq(19_600L, o.payout(), "payout");
			eq(creatorWins, o.creatorWon(), "forced outcome");
			eq(creatorWins ? 109_600L : 90_000L, EconomyService.balance(a.getUUID()), "creator balance");
			eq(creatorWins ? 90_000L : 109_600L, EconomyService.balance(b.getUUID()), "accepter balance");
			eq(tr0 + 400, Treasury.balance(), "fee in the treasury");
			eq(0L, cf.escrow(), "escrow empty");
			eq(t0, total(a.getUUID(), b.getUUID(), cf), "money preserved (accounts + escrow + treasury)");
			isTrue(cf.find(r.flip().id).isEmpty(), "flip removed");
			isFalse(cf.accept(b.getUUID(), "MiniB", r.flip().id, 10_000).ok(), "second accept fails");
		}
		eq(1L, PlayerDataService.getCounter(a.getUUID(), Coinflips.C_WINS), "creator wins");
		eq(1L, PlayerDataService.getCounter(b.getUUID(), Coinflips.C_WINS), "acceptor wins");
		Coinflips random = coinflips(cfg, new Coinflips.Data(), 99);
		EconomyService.set(a.getUUID(), 10_000_000);
		EconomyService.set(b.getUUID(), 10_000_000);
		long t0 = total(a.getUUID(), b.getUUID(), random);
		int creatorWins = 0;
		for (int i = 0; i < 200; i++) {
			Coinflips.Result r = random.create(a.getUUID(), "MiniA", 150 + i);
			Coinflips.Outcome o = random.accept(b.getUUID(), "MiniB", r.flip().id, 150 + i);
			isTrue(o.ok(), "round " + i);
			eq(o.pot(), o.fee() + o.payout(), "pot = fee + payout");
			eq(Money.percentOf(o.pot(), 2.0), o.fee(), "fee rounded down");
			if (o.creatorWon()) creatorWins++;
		}
		eq(t0, total(a.getUUID(), b.getUUID(), random), "sum unchanged after 200 rounds");
		isTrue(creatorWins > 60 && creatorWins < 140, "about 50/50 (" + creatorWins + "/200)");
		ctx.detail("2 % fee, both outcomes, 200 random rounds preserved (creator " + creatorWins + "/200)");
	}

	private static void coinflipEdgeCases(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		FakePlayer c = ctx.player("MiniC");
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.coinflip.minBet = 100;
		cfg.coinflip.maxBet = 100_000;
		cfg.coinflip.maxOpenPerPlayer = 2;
		Coinflips.Data data = new Coinflips.Data();
		Coinflips cf = coinflips(cfg, data, 5);
		EconomyService.set(a.getUUID(), 50_000);
		EconomyService.set(b.getUUID(), 50_000);
		EconomyService.set(c.getUUID(), 50);
		isFalse(cf.create(a.getUUID(), "MiniA", 99).ok(), "below minimum bet");
		isFalse(cf.create(a.getUUID(), "MiniA", 100_001).ok(), "above maximum bet");
		isFalse(cf.create(a.getUUID(), "MiniA", 0).ok(), "0");
		isFalse(cf.create(a.getUUID(), "MiniA", -5).ok(), "negative");
		Coinflips.Result poor = cf.create(c.getUUID(), "MiniC", 100);
		isFalse(poor.ok(), "not enough money");
		eq(50L, EconomyService.balance(c.getUUID()), "account unchanged");
		Coinflips.Result f1 = cf.create(a.getUUID(), "MiniA", 1_000);
		Coinflips.Result f2 = cf.create(a.getUUID(), "MiniA", 2_000);
		isTrue(f1.ok() && f2.ok(), "two flips");
		isFalse(cf.create(a.getUUID(), "MiniA", 3_000).ok(), "at most 2 open");
		eq(47_000L, EconomyService.balance(a.getUUID()), "both stakes reserved");
		isFalse(cf.accept(a.getUUID(), "MiniA", f1.flip().id, 1_000).ok(), "cannot accept own");
		isFalse(cf.accept(b.getUUID(), "MiniB", "nonexistent", 1_000).ok(), "unknown id");
		isFalse(cf.accept(b.getUUID(), "MiniB", f1.flip().id, 999).ok(), "different stake expected");
		isFalse(cf.accept(c.getUUID(), "MiniC", f1.flip().id, 1_000).ok(), "acceptor without money");
		eq(50L, EconomyService.balance(c.getUUID()), "acceptor without money unchanged");
		isTrue(cf.find(f1.flip().id).isPresent(), "flip stays open");
		isFalse(cf.cancel(b.getUUID(), f1.flip().id).ok(), "cannot cancel someone else's");
		Coinflips.Result cancel = cf.cancel(a.getUUID(), f1.flip().id);
		isTrue(cancel.ok(), "cancel own");
		eq(48_000L, EconomyService.balance(a.getUUID()), "refund");
		isFalse(cf.cancel(a.getUUID(), f1.flip().id).ok(), "cannot cancel twice");
		isFalse(cf.accept(b.getUUID(), "MiniB", f1.flip().id, 1_000).ok(), "cannot accept a cancelled flip");
		eq(2_000L, cf.cancelAll(a.getUUID()), "cancel all");
		eq(50_000L, EconomyService.balance(a.getUUID()), "all refunded");
		eq(0, data.open.size(), "no open flips");
		cfg.coinflip.maxBet = Money.MAX / 4;
		Coinflips.Result big = cf.create(a.getUUID(), "MiniA", 10_000);
		EconomyService.set(a.getUUID(), Money.MAX - 100);
		Coinflips.Outcome over = cf.accept(b.getUUID(), "MiniB", big.flip().id, 10_000);
		isFalse(over.ok(), "account limit of the possible winner");
		eq(50_000L, EconomyService.balance(b.getUUID()), "nothing debited");
		isTrue(cf.find(big.flip().id).isPresent(), "flip stays");
		EconomyService.set(a.getUUID(), 0);
		isTrue(cf.cancel(a.getUUID(), big.flip().id).ok(), "clean up");
		eq(10_000L, EconomyService.balance(a.getUUID()), "refund");
		eq(40L, cf.feeFor(2_000), "feeFor 2 %");
		eq(3L, cf.feeFor(150), "feeFor rounded down");
		eq(0L, cf.feeFor(1), "feeFor 1 cent");
	}

	private static void coinflipRefundOnStop(TestContext ctx) {
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		FakePlayer c = ctx.player("MiniC");
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.coinflip.minBet = 100;
		Coinflips.Data data = new Coinflips.Data();
		Coinflips cf = coinflips(cfg, data, 11);
		EconomyService.set(a.getUUID(), 10_000);
		EconomyService.set(b.getUUID(), 10_000);
		EconomyService.set(c.getUUID(), 10_000);
		cf.create(a.getUUID(), "MiniA", 1_000);
		cf.create(a.getUUID(), "MiniA", 2_000);
		cf.create(b.getUUID(), "MiniB", 5_000);
		Coinflips.Result stuck = cf.create(c.getUUID(), "MiniC", 500);
		EconomyService.set(c.getUUID(), Money.MAX - 10);
		eq(8_500L, cf.escrow(), "escrow before the stop");
		int n = cf.refundAll("server stop");
		eq(3, n, "three flips refunded");
		eq(10_000L, EconomyService.balance(a.getUUID()), "A fully refunded");
		eq(10_000L, EconomyService.balance(b.getUUID()), "B fully refunded");
		eq(1, data.open.size(), "flip that cannot be refunded is kept");
		eq(stuck.flip().id, data.open.get(0).id, "it is C's flip");
		isTrue(PlayerDataService.getPendingMessages(a.getUUID()).stream().anyMatch(m -> m.contains("refunded")),
			"offline creator gets a message on next join");
		EconomyService.set(c.getUUID(), 0);
		eq(1, cf.refundAll("restart"), "refunded on the next attempt");
		eq(500L, EconomyService.balance(c.getUUID()), "C refunded");
		eq(0L, cf.escrow(), "escrow empty");
	}

	/** Next(bits) is the core of all Random methods, so this counts every draw. */
	private static final class CountingRandom extends Random {
		int calls;

		CountingRandom(long seed) {
			super(seed);
		}

		@Override
		protected int next(int bits) {
			calls++;
			return super.next(bits);
		}
	}

	/**
	 * Creating and cancelling must not advance the generator that decides the outcome, or "/coinflip &lt;min&gt;" +
	 * "/coinflip cancel" could steer it to a favourable result.
	 */
	private static void coinflipRandomSeparated(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.coinflip.minBet = 100;
		cfg.coinflip.maxOpenPerPlayer = 5;
		CountingRandom rnd = new CountingRandom(1);
		Coinflips.Data data = new Coinflips.Data();
		Coinflips cf = new Coinflips(() -> data, () -> {}, () -> cfg.coinflip, rnd, () -> 1_000_000L);
		EconomyService.set(a.getUUID(), 100_000);
		EconomyService.set(b.getUUID(), 100_000);
		for (int i = 0; i < 20; i++) {
			Coinflips.Result r = cf.create(a.getUUID(), "MiniA", 1_000);
			isTrue(r.ok(), "created: " + r.error());
			isTrue(cf.cancel(a.getUUID(), r.flip().id).ok(), "cancelled");
		}
		eq(0, rnd.calls, "creating/cancelling draws nothing from the outcome RNG");
		eq(100_000L, EconomyService.balance(a.getUUID()), "all refunded");
		Coinflips.Result r = cf.create(a.getUUID(), "MiniA", 1_000);
		isTrue(cf.accept(b.getUUID(), "MiniB", r.flip().id, 1_000).ok(), "accepted");
		isTrue(rnd.calls > 0, "only accepting draws the outcome");
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (long seq = 1; seq <= 5_000; seq++) {
			String id = Coinflips.newId(seq);
			eq(Long.toString(seq, 36).length() + 2, id.length(), "fixed suffix width: " + id);
			isTrue(id.startsWith(Long.toString(seq, 36)), "prefix = sequence number: " + id);
			isTrue(ids.add(id), "id unique: " + id);
		}
		ctx.detail("20× create+cancel without RNG draw, 5000 unique ids");
	}

	private static void coinflipAnnouncementGate(TestContext ctx) {
		MinigamesConfig def = new MinigamesConfig();
		eq(500_000L, def.coinflip.announceMinBet, "default threshold $5,000");
		eq(60, def.coinflip.announceCooldownSeconds, "default cooldown 60 s");
		MinigamesConfig.validate(def);
		isFalse(def.announceMinBetMigrated, "default is not migrated");

		MinigamesConfig cfg = new MinigamesConfig();
		long start = 5_000_000L;
		long[] now = {start};
		Coinflips.Data data = new Coinflips.Data();
		Coinflips cf = new Coinflips(() -> data, () -> {}, () -> cfg.coinflip, new Random(1), () -> now[0]);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		isFalse(cf.claimCreateAnnouncement(a, 10_000), "$100 flip is not announced");
		isFalse(cf.announcesResult(10_000), "$100 result is not announced");
		isFalse(cf.claimCreateAnnouncement(a, 499_999), "not just below the threshold");
		isTrue(cf.claimCreateAnnouncement(a, 500_000), "announced from $5,000 (below the threshold uses no cooldown)");
		isTrue(cf.announcesResult(500_000), "result announced from $5,000");
		int extra = 0;
		for (int i = 0; i < 30; i++) {
			now[0] += 1_000;
			if (cf.claimCreateAnnouncement(a, 1_000_000)) extra++;
		}
		eq(0, extra, "no further announcement within the cooldown");
		isTrue(cf.claimCreateAnnouncement(b, 500_000), "other player has their own cooldown");
		now[0] = start + 59_999;
		isFalse(cf.claimCreateAnnouncement(a, 500_000), "59.999 s: still blocked");
		now[0] = start + 60_000;
		isTrue(cf.claimCreateAnnouncement(a, 500_000), "announced again after 60 s");
		isFalse(cf.claimCreateAnnouncement(a, 500_000), "and then blocked again");
		now[0] = start - 3_600_000;
		isTrue(cf.claimCreateAnnouncement(b, 500_000), "clock set back: no permanent block");
		cfg.coinflip.announceCooldownSeconds = 0;
		isTrue(cf.claimCreateAnnouncement(a, 500_000) && cf.claimCreateAnnouncement(a, 500_000), "cooldown 0: every time");
		isFalse(cf.claimCreateAnnouncement(a, 10_000), "cooldown 0: threshold still applies");

		MinigamesConfig old = DataStore.gson().fromJson("{\"coinflip\":{\"minBet\":10000,\"announceMinBet\":0}}", MinigamesConfig.class);
		MinigamesConfig.validate(old);
		eq(MinigamesConfig.Coinflip.DEFAULT_ANNOUNCE_MIN_BET, old.coinflip.announceMinBet, "old default 0 becomes $5,000");
		eq(60, old.coinflip.announceCooldownSeconds, "missing cooldown: default 60 s");
		isTrue(old.announceMinBetMigrated, "migration reported (minigames.json is written back)");
		isFalse(DataStore.gson().toJson(old).contains("announceMinBetMigrated"), "migration flag does not end up in the file");
		MinigamesConfig all = new MinigamesConfig();
		all.coinflip.announceMinBet = 1;
		MinigamesConfig.validate(all);
		eq(1L, all.coinflip.announceMinBet, "1 (= announce every flip) stays");
		isFalse(all.announceMinBetMigrated, "1 is not migrated");
		MinigamesConfig bad = new MinigamesConfig();
		bad.coinflip.announceCooldownSeconds = -1;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> MinigamesConfig.validate(bad), "negative cooldown rejected");
		ctx.detail("threshold $5,000, 1 announcement per 60 s and player, old default 0 migrated");
	}

	private static void coinflipPersistence() {
		Coinflips.Data data = new Coinflips.Data();
		Coinflips.Flip f = new Coinflips.Flip();
		f.id = "abc";
		f.creator = UUID.randomUUID();
		f.creatorName = "Levi";
		f.amount = 12_345;
		f.createdAt = 42;
		data.open.add(f);
		Coinflips.Flip broken = new Coinflips.Flip();
		broken.id = "broken";
		data.open.add(broken);
		data.seq = 7;
		String json = DataStore.gson().toJson(data);
		Coinflips.Data back = DataStore.gson().fromJson(json, Coinflips.Data.class);
		back.fixNulls();
		eq(1, back.open.size(), "broken entry (no creator) is discarded");
		Coinflips.Flip g = back.open.get(0);
		eq(f.id, g.id, "id");
		eq(f.creator, g.creator, "creator");
		eq(f.creatorName, g.creatorName, "Name");
		eq(f.amount, g.amount, "amount");
		eq(f.createdAt, g.createdAt, "time");
		eq(7L, back.seq, "seq");
		Coinflips.Data empty = DataStore.gson().fromJson("{\"open\":null}", Coinflips.Data.class);
		empty.fixNulls();
		eq(0, empty.open.size(), "null list repaired");
	}

	private static TreasureHunt treasure(MinigamesConfig cfg, TreasureHunt.Data data, long[] now, Recorder rec, long seed) {
		cfg.treasure.enabled = false;
		return new TreasureHunt(() -> data, () -> {}, () -> cfg.treasure, () -> now[0], new Random(seed), rec);
	}

	private static void treasureHints() {
		eq("north", TreasureHunt.direction(0, -100), "north = -Z");
		eq("east", TreasureHunt.direction(100, 0), "east = +X");
		eq("south", TreasureHunt.direction(0, 100), "south");
		eq("west", TreasureHunt.direction(-100, 0), "west");
		eq("northeast", TreasureHunt.direction(100, -100), "northeast");
		eq("southeast", TreasureHunt.direction(100, 100), "southeast");
		eq("southwest", TreasureHunt.direction(-100, 100), "southwest");
		eq("northwest", TreasureHunt.direction(-100, -100), "northwest");
		eq("north", TreasureHunt.direction(20, -100), "slightly east of north stays north");
		eq("here", TreasureHunt.direction(0, 0), "exactly at spawn");
		int[] r = TreasureHunt.distanceRange(734);
		eq(700, r[0], "distance low");
		eq(800, r[1], "distance high");
		eq(300, TreasureHunt.distanceRange(300)[0], "exactly 300");
		MinigamesConfig cfg = new MinigamesConfig();
		TreasureHunt.Active a = new TreasureHunt.Active();
		a.startedAt = 1_000;
		eq(0, TreasureHunt.stageAt(a, 1_000 + 5 * 60_000 - 1, cfg.treasure), "before 5 min level 0");
		eq(1, TreasureHunt.stageAt(a, 1_000 + 5 * 60_000, cfg.treasure), "from 5 min level 1");
		eq(2, TreasureHunt.stageAt(a, 1_000 + 10 * 60_000, cfg.treasure), "from 10 min level 2");
		Component biome = TreasureHunt.biomeName("minecraft:plains");
		isTrue(biome.getContents() instanceof TranslatableContents t && t.getKey().equals("biome.minecraft.plains"), "biome as translation key");
		long[] now = {0};
		Recorder rec = new Recorder();
		TreasureHunt hunt = treasure(cfg, new TreasureHunt.Data(), now, rec, 77);
		Map<String, MinigamesConfig.Loot> byId = new java.util.HashMap<>();
		for (MinigamesConfig.Loot l : cfg.treasure.loot) byId.put(l.item, l);
		for (int i = 0; i < 300; i++) {
			TreasureHunt.Active x = new TreasureHunt.Active();
			hunt.rollRewards(x, cfg.treasure);
			isTrue(x.money >= cfg.treasure.minMoney && x.money <= cfg.treasure.maxMoney, "money in bounds: " + x.money);
			int sum = 0;
			for (TreasureHunt.RewardItem it : x.items) {
				MinigamesConfig.Loot l = byId.get(it.item);
				isTrue(l != null, "item from the table: " + it.item);
				isTrue(it.count >= l.min && it.count <= l.max * cfg.treasure.itemRolls, "count in bounds: " + it.item + " x" + it.count);
				sum++;
			}
			isTrue(sum >= 1 && sum <= cfg.treasure.itemRolls, "rolls: " + sum);
			isTrue(x.commonKeys <= 1 && x.rareKeys <= 1, "at most one key per kind");
		}
	}

	private static void treasureSafeSpot() {
		var stone = Blocks.STONE.defaultBlockState();
		var air = Blocks.AIR.defaultBlockState();
		isTrue(TreasureHunt.safeStates(stone, true, air, air), "stone + air");
		isTrue(TreasureHunt.safeStates(Blocks.GRASS_BLOCK.defaultBlockState(), true, Blocks.SHORT_GRASS.defaultBlockState(), air), "grass is replaceable");
		isFalse(TreasureHunt.safeStates(Blocks.WATER.defaultBlockState(), false, air, air), "water as ground");
		isFalse(TreasureHunt.safeStates(Blocks.OAK_LEAVES.defaultBlockState(), true, air, air), "leaves as ground");
		isFalse(TreasureHunt.safeStates(Blocks.MAGMA_BLOCK.defaultBlockState(), true, air, air), "magma as ground");
		isFalse(TreasureHunt.safeStates(stone, true, Blocks.WATER.defaultBlockState(), air), "in water");
		isFalse(TreasureHunt.safeStates(stone, true, air, Blocks.LAVA.defaultBlockState()), "lava above");
		isFalse(TreasureHunt.safeStates(stone, true, stone, air), "spot occupied");
		isFalse(TreasureHunt.safeStates(stone, false, air, air), "ground not solid");
		isFalse(TreasureHunt.safeStates(stone, true, air, stone), "no room above");
		// natural ground only: PlacedBlockTracker doesn't know builds from before the mod
		for (var b : List.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.SAND, Blocks.RED_SAND,
			Blocks.GRAVEL, Blocks.CLAY, Blocks.SNOW_BLOCK, Blocks.DEEPSLATE, Blocks.ANDESITE, Blocks.CALCITE, Blocks.SANDSTONE,
			Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA)) {
			isTrue(TreasureHunt.isNaturalGround(b.defaultBlockState()), "natural ground: " + b);
			isTrue(TreasureHunt.safeStates(b.defaultBlockState(), true, air, air), "natural ground ok: " + b);
		}
		for (var b : List.of(Blocks.STONE_BRICKS, Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.GLASS,
			Blocks.PINK_TERRACOTTA, Blocks.WHITE_CONCRETE, Blocks.POLISHED_ANDESITE, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.OAK_LOG,
			Blocks.POWDER_SNOW, Blocks.PACKED_ICE, Blocks.SUSPICIOUS_SAND, Blocks.BEDROCK, Blocks.CHEST)) {
			isFalse(TreasureHunt.isNaturalGround(b.defaultBlockState()), "not natural ground: " + b);
			isFalse(TreasureHunt.safeStates(b.defaultBlockState(), true, air, air), "built ground rejected: " + b);
		}
		for (var b : List.of(Blocks.TORCH, Blocks.LANTERN, Blocks.GLOWSTONE, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL, Blocks.OAK_DOOR,
			Blocks.OAK_TRAPDOOR, Blocks.OAK_STAIRS, Blocks.STONE_SLAB, Blocks.WHITE_WOOL, Blocks.WHITE_CARPET, Blocks.GLASS_PANE,
			Blocks.CHEST, Blocks.FURNACE, Blocks.RED_BED, Blocks.OAK_SIGN, Blocks.CRAFTING_TABLE, Blocks.RAIL, Blocks.LAVA, Blocks.STONE_BRICKS,
			Blocks.OAK_PLANKS, Blocks.COBBLESTONE)) {
			isTrue(TreasureHunt.looksBuilt(b.defaultBlockState()), "built: " + b);
		}
		for (var b : List.of(Blocks.AIR, Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SHORT_GRASS, Blocks.POPPY, Blocks.OAK_LOG,
			Blocks.OAK_LEAVES, Blocks.SAND, Blocks.SNOW, Blocks.BROWN_MUSHROOM, Blocks.SUGAR_CANE, Blocks.WATER)) {
			isFalse(TreasureHunt.looksBuilt(b.defaultBlockState()), "natural: " + b);
		}
	}

	private static void treasureNoBuilds(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		BlockPos spawn = TreasureHunt.spawnOf(level);
		BlockPos pos = new BlockPos(spawn.getX(), level.getMaxY() - 6, spawn.getZ());
		int r = TreasureHunt.CLEAR_RADIUS;
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r - 1, -3, -r - 1), pos.offset(r + 1, 4, r + 1))) {
			if (!level.getBlockState(p).isAir()) {
				ctx.detail("skipped: area at the world ceiling above spawn is not free");
				return;
			}
		}
		List<BlockPos> touched = new ArrayList<>();
		ctx.cleanup(() -> {
			for (BlockPos p : touched) {
				PlacedBlockTracker.unmark(level, p);
				if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
			}
		});
		java.util.function.BiConsumer<BlockPos, net.minecraft.world.level.block.Block> set = (p, b) -> {
			BlockPos at = p.immutable();
			if (!touched.contains(at)) touched.add(at);
			level.setBlock(at, b.defaultBlockState(), 3);
		};
		BlockPos ground = pos.below();

		set.accept(ground, Blocks.STONE);
		isTrue(TreasureHunt.isSafeSpot(level, pos), "stone in the open is safe");
		set.accept(ground, Blocks.STONE_BRICKS);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "stone brick roof rejected");
		set.accept(ground, Blocks.OAK_PLANKS);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "plank floor rejected");
		set.accept(ground, Blocks.COBBLESTONE);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "cobblestone path rejected");
		set.accept(ground, Blocks.STONE);
		isTrue(TreasureHunt.isSafeSpot(level, pos), "stone again: safe");

		set.accept(pos.offset(r, 0, -r), Blocks.OAK_FENCE);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "fence at the edge of the radius");
		set.accept(pos.offset(r, 0, -r), Blocks.AIR);
		set.accept(pos.offset(-2, 3, 1), Blocks.GLOWSTONE);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "light source above the spot");
		set.accept(pos.offset(-2, 3, 1), Blocks.AIR);
		set.accept(ground.below(), Blocks.OAK_PLANKS);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "natural block on a plank roof");
		set.accept(ground.below(), Blocks.AIR);
		set.accept(pos.offset(r + 1, 0, 0), Blocks.OAK_FENCE);
		isTrue(TreasureHunt.isSafeSpot(level, pos), "fence outside the radius is fine");
		set.accept(pos.offset(r + 1, 0, 0), Blocks.AIR);

		BlockPos dirt = pos.offset(1, 0, 2);
		set.accept(dirt, Blocks.DIRT);
		isTrue(TreasureHunt.isSafeSpot(level, pos), "natural dirt next to it is ok");
		PlacedBlockTracker.markPlaced(level, dirt);
		isFalse(TreasureHunt.isSafeSpot(level, pos), "player-placed dirt next to it rejected");
		PlacedBlockTracker.unmark(level, dirt);
		ctx.detail("built ground, fence, light, roof under natural block, tracker: rejected; natural: safe");
	}

	private static void treasureReach(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		Optional<BlockPos> free = testPos(level);
		if (free.isEmpty()) {
			ctx.detail("skipped: world ceiling above spawn is not free");
			return;
		}
		BlockPos pos = free.get();
		ctx.cleanup(() -> {
			if (level.getBlockState(pos).is(Blocks.CHEST)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		});
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.treasure.minMoney = 5_000;
		cfg.treasure.maxMoney = 5_000;
		long[] now = {300_000_000};
		Recorder rec = new Recorder();
		TreasureHunt hunt = treasure(cfg, new TreasureHunt.Data(), now, rec, 13);
		FakePlayer a = ctx.player("MiniA");
		EconomyService.set(a.getUUID(), 0);
		isTrue(hunt.place(level, pos, false) != null, "chest hidden");

		// left-click packet (AttackBlockCallback runs before vanilla's reach check) from 20 blocks away
		a.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 20.5);
		isFalse(a.isWithinBlockInteractionRange(pos, 1.0), "20 blocks away: out of reach");
		isFalse(TreasureHunt.canReach(a, level, pos), "canReach from afar");
		eq(InteractionResult.PASS, MinigamesFeature.treasureClick(hunt, a, level, pos), "distant click: PASS (vanilla discards it)");
		isTrue(hunt.active().isPresent(), "treasure stays active");
		isTrue(level.getBlockState(pos).is(Blocks.CHEST), "chest still there");
		eq(0L, EconomyService.balance(a.getUUID()), "no money from afar");
		a.setPos(pos.getX() + 0.5, level.getMinY() + 80, pos.getZ() + 0.5);
		eq(InteractionResult.PASS, MinigamesFeature.treasureClick(hunt, a, level, pos), "click from far below: PASS");
		isTrue(hunt.active().isPresent(), "treasure still active");

		a.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
		isTrue(a.isWithinBlockInteractionRange(pos, 1.0), "2 blocks away: in reach");
		isTrue(MinigamesFeature.treasureClick(hunt, a, level, pos) != InteractionResult.SUCCESS, "FakePlayer cannot win");
		isTrue(hunt.active().isPresent(), "treasure still there");
		eq(InteractionResult.PASS, MinigamesFeature.treasureClick(hunt, a, level, pos.above()), "other block: PASS");
		hunt.cleanup(ctx.server(), null);
		isTrue(level.getBlockState(pos).isAir(), "cleaned up");
		ctx.detail("distant left click: PASS without a find, close range detected");
	}

	private static void treasureFromTreasury(TestContext ctx) {
		eq(0L, TreasureHunt.capMoney(500_000, 0), "no budget: no money");
		eq(125_000L, TreasureHunt.capMoney(500_000, 125_000), "capped to the budget");
		eq(124_000L, TreasureHunt.capMoney(500_000, 124_999), "rounded down to whole $10");
		eq(300_000L, TreasureHunt.capMoney(300_000, 1_000_000), "below the budget unchanged");
		eq(999L, TreasureHunt.capMoney(999, 5_000), "small amount stays");
		eq(0L, TreasureHunt.capMoney(500, -5), "negative budget");

		ServerLevel level = ctx.overworld();
		Optional<BlockPos> free = testPos(level);
		if (free.isEmpty()) {
			ctx.detail("only the arithmetic checked: world ceiling above spawn is not free");
			return;
		}
		BlockPos pos = free.get();
		ctx.cleanup(() -> {
			if (level.getBlockState(pos).is(Blocks.CHEST)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		});
		ctx.preserveTreasury();
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.treasure.rewardsFromTreasury = true;
		cfg.treasure.minMoney = Money.MAX / 1_000_000 * 1_000;
		cfg.treasure.maxMoney = cfg.treasure.minMoney;
		cfg.treasure.loot = new ArrayList<>();
		cfg.treasure.itemRolls = 0;
		cfg.treasure.commonKeyChance = 0;
		cfg.treasure.rareKeyChance = 0;
		long[] now = {400_000_000};
		Recorder rec = new Recorder();
		TreasureHunt hunt = treasure(cfg, new TreasureHunt.Data(), now, rec, 17);
		FakePlayer a = ctx.player("MiniA");
		UUID id = a.getUUID();
		EconomyService.set(id, 0);
		long budget = Treasury.maxSpendPerHour();
		TreasureHunt.Active t = hunt.place(level, pos, false);
		isTrue(t != null, "chest hidden");
		isTrue(t.fromTreasury, "money from the treasury");
		isTrue(t.money <= budget, "reward <= hourly budget (" + t.money + " ≤ " + budget + ")");
		eq(TreasureHunt.capMoney(cfg.treasure.minMoney, budget), t.money, "capped to the hourly budget");
		long treasury = Treasury.balance();
		Optional<TreasureHunt.Claim> claim = hunt.claim(a);
		isTrue(claim.isPresent(), "Fund");
		TreasureHunt.Claim c = claim.get();
		isTrue(c.payError() == null, "no payout error");
		eq(t.money, c.money() + c.pendingMoney(), "immediate + pending = reward");
		eq(treasury - c.money(), Treasury.balance(), "treasury pays the immediate part");
		eq(c.money(), EconomyService.balance(id), "player gets the immediate part");
		eq(c.pendingMoney(), TreasuryRewards.pending(id), "rest recorded as pending");
		isTrue(level.getBlockState(pos).isAir(), "chest removed");
		ctx.detail("reward " + Money.format(t.money) + " (budget " + Money.format(budget) + "), immediate " + Money.format(c.money())
			+ ", pending " + Money.format(c.pendingMoney()));
	}

	private static Optional<BlockPos> testPos(ServerLevel level) {
		BlockPos spawn = TreasureHunt.spawnOf(level);
		BlockPos pos = new BlockPos(spawn.getX(), level.getMaxY() - 1, spawn.getZ());
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) return Optional.empty();
		return Optional.of(pos);
	}

	private static void treasureClaimOnce(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		Optional<BlockPos> free = testPos(level);
		if (free.isEmpty()) {
			ctx.detail("skipped: world ceiling above spawn is not free");
			return;
		}
		BlockPos pos = free.get();
		ctx.cleanup(() -> {
			if (level.getBlockState(pos).is(Blocks.CHEST)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		});
		MinigamesConfig cfg = new MinigamesConfig();
		cfg.treasure.minMoney = 12_300;
		cfg.treasure.maxMoney = 12_300;
		cfg.treasure.loot = new ArrayList<>(List.of(new MinigamesConfig.Loot("minecraft:diamond", 3, 3, 1)));
		cfg.treasure.itemRolls = 1;
		cfg.treasure.commonKeyChance = 1.0;
		cfg.treasure.rareKeyChance = 0.0;
		cfg.treasure.coordinateFuzz = 50;
		long[] now = {100_000_000};
		Recorder rec = new Recorder();
		TreasureHunt.Data data = new TreasureHunt.Data();
		TreasureHunt hunt = treasure(cfg, data, now, rec, 9);
		FakePlayer a = ctx.player("MiniA");
		FakePlayer b = ctx.player("MiniB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);

		TreasureHunt.Active t = hunt.place(level, pos, true);
		isTrue(t != null, "chest hidden");
		isTrue(level.getBlockState(pos).is(Blocks.CHEST), "chest stands in the world");
		isTrue(hunt.isTreasure(level, pos), "position detected");
		isFalse(hunt.isTreasure(level, pos.above()), "neighbouring block is no chest");
		Level nether = ctx.server().getLevel(Level.NETHER);
		if (nether != null) isFalse(hunt.isTreasure(nether, pos), "other dimension");
		isTrue(Math.abs(t.hintX - t.x) <= 50 && Math.abs(t.hintZ - t.z) <= 50, "coordinate hint ±50");
		isTrue(rec.any("TITLE") && rec.any("A treasure has been hidden") && rec.any("Hint 1"), "start announced with hint");
		isFalse(rec.any("Hint 2"), "no further hints yet");
		eq(12_300L, t.money, "reward fixed in advance");
		long keys0 = CrateKeys.count(a.getUUID(), CrateKeys.COMMON);

		Optional<TreasureHunt.Claim> first = hunt.claim(a);
		isTrue(first.isPresent(), "first click wins");
		Optional<TreasureHunt.Claim> second = hunt.claim(b);
		isTrue(second.isEmpty(), "second click finds nothing");
		eq(12_300L, EconomyService.balance(a.getUUID()), "money credited");
		eq(0L, EconomyService.balance(b.getUUID()), "second gets nothing");
		eq(3, Items.countPristine(a, net.minecraft.world.item.Items.DIAMOND), "items in inventory");
		eq(keys0 + 1, CrateKeys.count(a.getUUID(), CrateKeys.COMMON), "key via CrateKeys");
		isTrue(level.getBlockState(pos).isAir(), "chest removed");
		isTrue(hunt.active().isEmpty(), "treasure hunt ended");
		eq("MiniA", data.lastWinner, "winner recorded");
		isTrue(rec.any("MiniA found the treasure"), "find announced");
		eq(1L, PlayerDataService.getCounter(a.getUUID(), TreasureHunt.C_FOUND), "find counter");

		FakePlayer c = ctx.player("MiniC");
		EconomyService.set(c.getUUID(), 0);
		for (int i = 0; i < Items.MAIN_SIZE; i++) c.getInventory().setItem(i, new ItemStack(net.minecraft.world.item.Items.STONE, 64));
		isTrue(hunt.place(level, pos, false) != null, "second chest");
		Optional<TreasureHunt.Claim> full = hunt.claim(c);
		isTrue(full.isPresent(), "find with full inventory");
		eq(3, full.get().dropped(), "3 items dropped");
		int dropped = ctx.drops(c).stream().filter(s -> s.is(net.minecraft.world.item.Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
		eq(3, dropped, "diamonds at their feet (ctx.drops)");
		eq(12_300L, EconomyService.balance(c.getUUID()), "money credited anyway");
		isTrue(level.getBlockState(pos).isAir(), "chest removed again");
		ctx.detail("find exactly once, money+items+key, full inventory drops");
	}

	private static void treasureTimeoutCleanup(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		Optional<BlockPos> free = testPos(level);
		if (free.isEmpty()) {
			ctx.detail("skipped: world ceiling above spawn is not free");
			return;
		}
		BlockPos pos = free.get();
		ctx.cleanup(() -> {
			if (level.getBlockState(pos).is(Blocks.CHEST)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		});
		MinigamesConfig cfg = new MinigamesConfig();
		long start = 200_000_000;
		long[] now = {start};
		Recorder rec = new Recorder();
		TreasureHunt.Data data = new TreasureHunt.Data();
		TreasureHunt hunt = treasure(cfg, data, now, rec, 21);

		TreasureHunt.Active t = hunt.place(level, pos, true);
		isTrue(t != null, "chest hidden");
		now[0] = start + 5 * 60_000;
		hunt.tick(ctx.server(), 1, 0);
		eq(1, t.stage, "level 1 after 5 min");
		isTrue(rec.any("Hint 2") && rec.any("blocks"), "distance hint");
		now[0] = start + 10 * 60_000;
		hunt.tick(ctx.server(), 1, 1);
		eq(2, t.stage, "level 2 after 10 min");
		isTrue(rec.any("Hint 3") && rec.any("X ≈ " + t.hintX), "coordinates hint");
		eq(1L, rec.count("Hint 3"), "hint only once");
		hunt.tick(ctx.server(), 1, 2);
		eq(1L, rec.count("Hint 3"), "no duplicate hint");

		now[0] = t.endsAt - 1;
		hunt.tick(ctx.server(), 1, 3);
		isTrue(hunt.active().isPresent(), "active just before expiry");
		now[0] = t.endsAt;
		hunt.tick(ctx.server(), 1, 4);
		isTrue(hunt.active().isEmpty(), "timeout ends");
		isTrue(level.getBlockState(pos).isAir(), "chest removed on timeout");
		isTrue(rec.any("Nobody found the treasure"), "timeout announced");

		now[0] = start;
		isTrue(hunt.place(level, pos, false) != null, "new chest");
		hunt.cleanup(ctx.server(), null);
		isTrue(level.getBlockState(pos).isAir(), "cleanup removes the chest");
		isTrue(hunt.active().isEmpty(), "no active hunt");

		isTrue(hunt.place(level, pos, false) != null, "third chest");
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		hunt.tick(ctx.server(), 1, 5);
		isTrue(hunt.active().isEmpty(), "missing chest ends the hunt");
		isTrue(rec.any("vanished"), "'disappeared' message");

		level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		ctx.cleanup(() -> {
			if (level.getBlockState(pos).is(Blocks.STONE)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		});
		eq(null, hunt.place(level, pos, false), "occupied spot: null");
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
	}
}
