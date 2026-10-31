package dev.smpeconomy.features.minigames.mega;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.minigames.MinigamesConfig;
import dev.smpeconomy.features.minigames.chat.Answers;
import dev.smpeconomy.features.minigames.chat.ChatGameContent;
import dev.smpeconomy.features.minigames.chat.ChatGames;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.GuessResult;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Outcome;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Phase;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Prepared;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Type;

/**
 * Generators over 10,000 samples per math kind and 12,000 trivia questions from the real registries (recalculated
 * independently or checked through other API paths), riddles and word scramble, answer format, exactly one winner,
 * attempts, persistence without double payment, scheduling, race measuring. Own engine instances.
 */
public final class MegaSelfTests {
	private static final String F = "minigames";
	private static final int MATH_SAMPLES = 10_000;
	private static final int TRIVIA_SAMPLES_PER_KIND = 2_000;

	private MegaSelfTests() {}

	public static void register(java.util.function.Supplier<MegaContent> content) {
		SelfTests.register(F, "mega-math-10k", ctx -> mathSamples(ctx));
		SelfTests.register(F, "mega-trivia-registry", ctx -> triviaSamples(ctx));
		SelfTests.register(F, "mega-riddle-unscramble", ctx -> words(ctx, content.get()));
		SelfTests.register(F, "mega-answer-format", ctx -> answerFormat(ctx));
		SelfTests.register(F, "mega-private-no-oracle", MegaSelfTests::privacyNoOracle);
		SelfTests.register(F, "mega-answer-space", ctx -> answerSpace(ctx));
		SelfTests.register(F, "mega-wait-secret", MegaSelfTests::hiddenDelay);
		SelfTests.register(F, "mega-participation-rules", MegaSelfTests::participationRules);
		SelfTests.register(F, "mega-one-winner", MegaSelfTests::singleWinner);
		SelfTests.register(F, "mega-ratelimit-mintime-attempts", MegaSelfTests::rateLimit);
		SelfTests.register(F, "mega-persistence-restart", MegaSelfTests::persistence);
		SelfTests.register(F, "mega-scheduling-chatgame-pause", MegaSelfTests::scheduling);
		SelfTests.register(F, "mega-race-measurement", MegaSelfTests::raceProbe);
		SelfTests.register(F, "mega-race-winner", MegaSelfTests::raceWinner);
		SelfTests.register(F, "mega-config-defaults", ctx -> configDefaults(ctx));
		SelfTests.register(F, "mega-first-attempt-only", MegaSelfTests::firstAttemptOnly);
		SelfTests.register(F, "mega-bonus-question", MegaSelfTests::bonusQuestion);
		SelfTests.register(F, "mega-night-race", MegaSelfTests::nightRace);
		SelfTests.register(F, "mega-config-schema", ctx -> configSchema());
	}

	static final class Rec implements MegaChallenge.Output {
		final List<String> lines = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		String bar;
		boolean barShown;

		@Override
		public void broadcast(Component message) {
			lines.add(message.getString());
		}

		@Override
		public void send(UUID player, Component message) {
			lines.add("@" + player + " " + message.getString());
		}

		@Override
		public void title(Component title, Component subtitle, MegaChallenge.Sound sound) {
			titles.add(title.getString() + "|" + subtitle.getString() + "|" + sound);
		}

		@Override
		public void bar(Component name, float progress, Phase phase) {
			bar = name.getString();
			barShown = true;
		}

		@Override
		public void hideBar() {
			barShown = false;
		}

		@Override
		public void actionBar(UUID player, Component message) {
			lines.add("#" + player + " " + message.getString());
		}

		boolean any(String part) {
			return lines.stream().anyMatch(l -> l.contains(part));
		}

		long count(String part) {
			return lines.stream().filter(l -> l.contains(part)).count();
		}

		boolean anyPublic(String part) {
			return lines.stream().anyMatch(l -> !l.startsWith("@") && !l.startsWith("#") && l.contains(part));
		}
	}

	static final class TestEnv implements MegaChallenge.Env {
		final Set<UUID> eligible = new LinkedHashSet<>();
		final Set<UUID> off = new HashSet<>();
		final Map<UUID, String> excluded = new HashMap<>();
		ChatGames chat;
		boolean chatRunning;
		long postponedUntil = -1;
		MegaConfig cfg;
		MegaContent content;
		Prepared forced;
		MegaChallenge.RaceProbe probe;
		long night;

		@Override
		public Set<UUID> eligible() {
			Set<UUID> s = new LinkedHashSet<>(eligible);
			s.removeAll(off);
			return s;
		}

		@Override
		public Map<UUID, String> exclusions(Set<UUID> eligible) {
			Map<UUID, String> m = new HashMap<>();
			for (UUID id : eligible) {
				if (excluded.containsKey(id)) m.put(id, excluded.get(id));
			}
			return m;
		}

		@Override
		public boolean chatGamesOn(UUID player) {
			return !off.contains(player);
		}

		@Override
		public boolean chatGameRunning() {
			return chat != null ? chat.active().isPresent() : chatRunning;
		}

		@Override
		public void stopChatGame(String message) {
			if (chat != null) chat.stop(message);
			chatRunning = false;
		}

		@Override
		public void postponeChatGames(long until) {
			postponedUntil = until;
			if (chat != null) chat.postpone(until);
		}

		@Override
		public Prepared prepare(Type type, String variant, Random random) {
			if (forced != null) return forced;
			return MegaGenerator.prepare(type, variant, cfg, content, () -> {
				throw new IllegalStateException("no trivia data in test");
			}, () -> List.of(), () -> MegaQuestion.Terms.NONE, () -> night, random);
		}

		@Override
		public MegaChallenge.RaceProbe raceProbe() {
			return probe;
		}

		@Override
		public long nightMillisLeft() {
			return night;
		}
	}

	private static MegaConfig testConfig() {
		MegaConfig c = new MegaConfig();
		c.reward = 1_000_000;
		c.epicKeyChance = 0;
		c.announceSeconds = 60;
		c.answerSeconds = 240;
		c.minAnswerDelaySeconds = 5;
		c.maxAnswerDelaySeconds = 5; // fixed for reproducible runs; mega-wait-secret tests the random wait
		// deliberately not the defaults: these tests cover the general mechanism with several attempts and a gap;
		// mega-first-attempt-only tests the first-attempt default
		c.guessCooldownSeconds = 3;
		c.maxGuesses = 4;
		c.maxTextGuesses = 3;
		c.bonusChance = 0; // scheduled starts are deterministic without BONUS; mega-bonus-question tests that
		return c;
	}

	private static MegaChallenge engine(MegaChallenge.Data data, MegaConfig cfg, long[] now, Rec rec, TestEnv env) {
		return new MegaChallenge(() -> data, () -> {}, () -> cfg, () -> now[0], new Random(99), rec, env);
	}

	static String text(MegaQuestion q) {
		StringBuilder sb = new StringBuilder();
		for (Component c : q.lines()) sb.append(c.getString()).append('\n');
		return sb.toString().replace('−', '-');
	}

	private static void mathSamples(TestContext ctx) {
		Random r = new Random(20251010L);
		Map<String, Integer> negatives = new HashMap<>();
		for (String kind : MegaMath.KINDS) {
			for (int i = 0; i < MATH_SAMPLES; i++) {
				MegaQuestion q = MegaMath.create(kind, r);
				String t = text(q);
				String expected = MathCheck.solve(kind, t);
				isTrue(q.numeric(), "numeric answer: " + kind);
				eq(expected, q.solutionPlain(), kind + ": solution of »" + t.trim() + "«");
				isTrue(q.isCorrect(expected), kind + ": solution accepted");
				isTrue(q.isCorrect(" " + expected + " "), kind + ": with spaces");
				if (!expected.startsWith("-")) isFalse(q.isCorrect(new BigInteger(expected).add(BigInteger.ONE).toString()), kind + ": +1 is wrong");
				if (expected.startsWith("-")) negatives.merge(kind, 1, Integer::sum);
				isFalse(q.explanation().isEmpty(), kind + ": explanation present");
				isTrue(q.explanation().stream().anyMatch(c -> c.getString().replace('−', '-').contains(expected)), kind + ": explanation names the solution " + expected);
			}
		}
		MegaQuestion big = null;
		for (int i = 0; i < 1000 && big == null; i++) {
			MegaQuestion q = MegaMath.create("binom", r);
			if (q.solutionPlain().length() >= 5) big = q;
		}
		Assert.notNull(big, "large number found");
		String s = big.solutionPlain();
		String grouped = groupThousands(s, '.');
		isTrue(big.isCorrect(grouped), "thousands dots: " + grouped);
		isTrue(big.isCorrect(groupThousands(s, ',')), "thousands commas");
		isTrue(big.isCorrect(groupThousands(s, ' ')), "thousands with spaces");
		ctx.detail(MegaMath.KINDS.size() + " kinds × " + MATH_SAMPLES + " tasks recalculated independently (negative solutions: " + negatives + ")");
	}

	private static String groupThousands(String digits, char sep) {
		StringBuilder sb = new StringBuilder();
		int n = digits.length();
		for (int i = 0; i < n; i++) {
			if (i > 0 && (n - i) % 3 == 0) sb.append(sep);
			sb.append(digits.charAt(i));
		}
		return sb.toString();
	}

	/** Recalculates independently from the displayed text (own parsers, brute force, BigInteger). */
	static final class MathCheck {
		private MathCheck() {}

		static String solve(String kind, String t) {
			return switch (kind) {
				case "quadratic" -> quadratic(t);
				case "linear_system" -> linear(t);
				case "modulo" -> {
					Matcher m = find("(\\d+)\\^(\\d+) divided by (\\d+)", t);
					yield new BigInteger(m.group(1)).modPow(new BigInteger(m.group(2)), new BigInteger(m.group(3))).toString();
				}
				case "gcd" -> {
					Matcher m = find("gcd\\((\\d+), (\\d+), (\\d+)\\)", t);
					yield big(m, 1).gcd(big(m, 2)).gcd(big(m, 3)).toString();
				}
				case "lcm" -> {
					Matcher m = find("lcm\\((\\d+), (\\d+), (\\d+)\\)", t);
					yield lcm(lcm(big(m, 1), big(m, 2)), big(m, 3)).toString();
				}
				case "divisors" -> {
					long n = Long.parseLong(find("sum of all positive divisors of (\\d+)\\?", t).group(1));
					long sum = 0;
					for (long d = 1; d * d <= n; d++) {
						if (n % d == 0) sum += d * d == n ? d : d + n / d;
					}
					yield Long.toString(sum);
				}
				case "series" -> series(find("sum: (.+)", t).group(1).trim());
				case "binom" -> {
					Matcher m = find("pick (\\d+) of (\\d+) players", t);
					int k = Integer.parseInt(m.group(1)), n = Integer.parseInt(m.group(2));
					BigInteger[] row = {BigInteger.ONE};
					for (int i = 1; i <= n; i++) {
						BigInteger[] next = new BigInteger[i + 1];
						next[0] = BigInteger.ONE;
						next[i] = BigInteger.ONE;
						for (int j = 1; j < i; j++) next[j] = row[j - 1].add(row[j]);
						row = next;
					}
					yield row[k].toString();
				}
				case "bases" -> base(t);
				case "expression" -> Long.toString(new ExprParser(find("Calculate: (.+)", t).group(1)).parse());
				default -> throw new AssertionError("unknown kind " + kind);
			};
		}

		private static Matcher find(String regex, String t) {
			Matcher m = Pattern.compile(regex).matcher(t);
			if (!m.find()) throw new AssertionError("text does not fit " + regex + ": " + t);
			return m;
		}

		private static BigInteger big(Matcher m, int g) {
			return new BigInteger(m.group(g));
		}

		private static BigInteger lcm(BigInteger a, BigInteger b) {
			return a.divide(a.gcd(b)).multiply(b);
		}

		private static long coef(String s) {
			if (s.isEmpty()) return 1;
			if (s.equals("-")) return -1;
			return Long.parseLong(s);
		}

		private static String quadratic(String t) {
			Matcher m = find("(-?\\d*)x² ([+-]) (\\d*)x ([+-]) (\\d+) = 0", t);
			long a = coef(m.group(1));
			long b = coef(m.group(3)) * (m.group(2).equals("-") ? -1 : 1);
			long c = Long.parseLong(m.group(5)) * (m.group(4).equals("-") ? -1 : 1);
			List<Long> roots = new ArrayList<>();
			for (long x = -2000; x <= 2000; x++) {
				if (a * x * x + b * x + c == 0) roots.add(x);
			}
			if (roots.size() != 2) throw new AssertionError("not exactly two integer solutions: " + roots + " in " + t);
			boolean larger = t.contains("LARGER");
			if (!larger && !t.contains("SMALLER")) throw new AssertionError("greater/smaller missing: " + t);
			return Long.toString(larger ? Math.max(roots.get(0), roots.get(1)) : Math.min(roots.get(0), roots.get(1)));
		}

		private static String linear(String t) {
			Pattern p = Pattern.compile("(-?\\d*)x ([+-]) (\\d*)y = (-?\\d+)");
			Matcher m = p.matcher(t);
			long[][] rows = new long[2][3];
			for (int i = 0; i < 2; i++) {
				if (!m.find()) throw new AssertionError("equation " + (i + 1) + " missing: " + t);
				rows[i][0] = coef(m.group(1));
				rows[i][1] = coef(m.group(3)) * (m.group(2).equals("-") ? -1 : 1);
				rows[i][2] = Long.parseLong(m.group(4));
			}
			if (!t.contains("x · y")) throw new AssertionError("asked x · y missing: " + t);
			List<long[]> sols = new ArrayList<>();
			for (long x = -60; x <= 60; x++) {
				long rest = rows[0][2] - rows[0][0] * x;
				if (rest % rows[0][1] != 0) continue;
				long y = rest / rows[0][1];
				if (rows[1][0] * x + rows[1][1] * y == rows[1][2]) sols.add(new long[] {x, y});
			}
			if (sols.size() != 1) throw new AssertionError("not exactly one solution (" + sols.size() + "): " + t);
			return Long.toString(sols.get(0)[0] * sols.get(0)[1]);
		}

		private static String series(String expr) {
			if (expr.contains("²") || expr.contains("³")) {
				boolean cube = expr.contains("³");
				Matcher m = find("1[²³] \\+ 2[²³] \\+ 3[²³] \\+ … \\+ (\\d+)[²³]", expr);
				long n = Long.parseLong(m.group(1));
				BigInteger sum = BigInteger.ZERO;
				for (long k = 1; k <= n; k++) sum = sum.add(BigInteger.valueOf(k).pow(cube ? 3 : 2));
				return sum.toString();
			}
			Matcher m = find("^(\\d+) \\+ (\\d+) \\+ (\\d+) \\+ … \\+ (\\d+)$", expr);
			long t1 = Long.parseLong(m.group(1)), t2 = Long.parseLong(m.group(2)), t3 = Long.parseLong(m.group(3)), last = Long.parseLong(m.group(4));
			BigInteger sum = BigInteger.ZERO;
			if (t2 - t1 == t3 - t2) {
				long d = t2 - t1;
				long v = t1;
				for (; v <= last; v += d) sum = sum.add(BigInteger.valueOf(v));
				if (v - d != last) throw new AssertionError("last term does not fit: " + expr);
			} else if (t2 % t1 == 0 && t2 / t1 == t3 / t2 && t3 % t2 == 0) {
				long q = t2 / t1;
				long v = t1;
				for (; v <= last; v *= q) sum = sum.add(BigInteger.valueOf(v));
				if (v / q != last) throw new AssertionError("last term does not fit: " + expr);
			} else {
				throw new AssertionError("neither arithmetic nor geometric: " + expr);
			}
			return sum.toString();
		}

		private static String base(String t) {
			Matcher from = Pattern.compile("Convert the number ([0-9A-Z]+) from base (\\d+)").matcher(t);
			if (from.find()) {
				int b = Integer.parseInt(from.group(2));
				long v = 0;
				for (char c : from.group(1).toCharArray()) {
					int d = c <= '9' ? c - '0' : c - 'A' + 10;
					if (d >= b) throw new AssertionError("digit " + c + " too large for base " + b);
					v = v * b + d;
				}
				return Long.toString(v);
			}
			Matcher to = find("Write the decimal number (\\d+) in base (\\d+)", t);
			long v = Long.parseLong(to.group(1));
			int b = Integer.parseInt(to.group(2));
			StringBuilder sb = new StringBuilder();
			while (v > 0) {
				sb.append((char) ('0' + v % b));
				v /= b;
			}
			return sb.reverse().toString();
		}
	}

	static final class ExprParser {
		private static final String SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹";
		private final String s;
		private int i;

		ExprParser(String s) {
			this.s = s.replace(" ", "").replace('−', '-');
		}

		long parse() {
			long v = sum();
			if (i != s.length()) throw new AssertionError("leftover in expression: " + s.substring(i) + " in " + s);
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
			long v = power();
			while (i < s.length() && (s.charAt(i) == '×' || s.charAt(i) == '÷')) {
				char op = s.charAt(i++);
				long r = power();
				if (op == '×') {
					v *= r;
				} else {
					if (r == 0 || v % r != 0) throw new AssertionError("division with remainder: " + v + " ÷ " + r + " in " + s);
					v /= r;
				}
			}
			return v;
		}

		private long power() {
			long v = atom();
			int start = i;
			while (i < s.length() && SUP.indexOf(s.charAt(i)) >= 0) i++;
			if (i > start) {
				int e = 0;
				for (int k = start; k < i; k++) e = e * 10 + SUP.indexOf(s.charAt(k));
				long r = 1;
				for (int k = 0; k < e; k++) r *= v;
				v = r;
			}
			return v;
		}

		private long atom() {
			if (s.charAt(i) == '(') {
				i++;
				long v = sum();
				if (i >= s.length() || s.charAt(i++) != ')') throw new AssertionError("missing parenthesis: " + s);
				return v;
			}
			int start = i;
			while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
			if (start == i) throw new AssertionError("number expected at " + i + ": " + s);
			return Long.parseLong(s.substring(start, i));
		}
	}

	@SuppressWarnings("unchecked")
	private static void triviaSamples(TestContext ctx) {
		MinecraftServer server = ctx.server();
		ServerLevel level = ctx.overworld();
		MegaNames.clearCache();
		MegaTrivia.Data d = MegaTrivia.build(server);
		for (String kind : MegaTrivia.KINDS) isTrue(MegaTrivia.available(kind, d), "data for " + kind);
		Map<Item, Integer> recipeCount = MegaTrivia.craftingRecipeCount(server);
		MegaQuestion.Terms vocabulary = MegaNames.vocabulary();
		Map<EntityType<?>, Integer> realHealth = new HashMap<>();
		Random r = new Random(4242);
		int total = 0;
		for (String kind : MegaTrivia.KINDS) {
			for (int i = 0; i < TRIVIA_SAMPLES_PER_KIND; i++, total++) {
				MegaQuestion q = MegaTrivia.create(kind, d, vocabulary, r);
				isTrue(q.isCorrect(q.solutionPlain()), kind + ": solution accepted: " + q.solutionPlain());
				switch (kind) {
					case "stack" -> {
						List<Item> items = (List<Item>) q.facts().get("items");
						List<Integer> slots = (List<Integer>) q.facts().get("slots");
						long sum = 0;
						int used = 0;
						Set<Integer> sizes = new HashSet<>();
						for (int k = 0; k < items.size(); k++) {
							int size = new ItemStack(items.get(k)).getMaxStackSize();
							sizes.add(size);
							sum += (long) slots.get(k) * size;
							used += slots.get(k);
							isTrue(mentions(q, items.get(k).getDescriptionId()), "stack: item named in the question");
						}
						eq(MegaTrivia.STACK_ITEMS, new HashSet<>(items).size(), "stack: five different items");
						isTrue(used <= 54, "stack: fits in a double chest");
						isTrue(sizes.size() >= 2, "stack: at least two stack sizes");
						eq(Long.toString(sum), q.solutionPlain(), "stack: sum");
					}
					case "health" -> {
						List<MegaTrivia.Mob> mobs = (List<MegaTrivia.Mob>) q.facts().get("mobs");
						List<Integer> counts = (List<Integer>) q.facts().get("counts");
						long sum = 0;
						Set<Integer> hps = new HashSet<>();
						for (int k = 0; k < mobs.size(); k++) {
							MegaTrivia.Mob m = mobs.get(k);
							int hp = realHealth.computeIfAbsent(m.type(), t -> createdHealth(t, level));
							eq(hp, m.health(), "health: freshly spawned mob has the base value " + m.type().getDescriptionId());
							sum += (long) counts.get(k) * hp;
							hps.add(hp);
							isTrue(counts.get(k) >= 2 && counts.get(k) <= 30, "health: count 2-30");
							isTrue(mentions(q, m.type().getDescriptionId()), "health: mob named in the question");
						}
						eq(3, new HashSet<>(mobs).size(), "health: three different mobs");
						eq(3, hps.size(), "health: three different health values");
						eq(Long.toString(sum), q.solutionPlain(), "health: sum");
					}
					case "ingredients", "yield" -> {
						List<MegaTrivia.Craft> crafts = (List<MegaTrivia.Craft>) q.facts().get("crafts");
						List<Integer> times = (List<Integer>) q.facts().get("times");
						long sum = 0;
						for (int k = 0; k < crafts.size(); k++) {
							MegaTrivia.Craft c = crafts.get(k);
							eq(1L, (long) recipeCount.getOrDefault(c.item(), 0), kind + ": exactly one crafting recipe for " + c.item());
							RecipeHolder<?> h = server.getRecipeManager().byKey(c.recipe()).orElseThrow();
							int[] viaDisplay = displaySlotsAndCount(h.value());
							eq(viaDisplay[0], c.slots(), kind + ": occupied slots per recipe display " + c.recipe());
							eq(viaDisplay[1], c.yield(), kind + ": result count per recipe display " + c.recipe());
							sum += (long) times.get(k) * (kind.equals("ingredients") ? viaDisplay[0] : viaDisplay[1]);
							isTrue(mentions(q, c.item().getDescriptionId()), kind + ": item named in the question");
						}
						eq(3, new HashSet<>(crafts).size(), kind + ": three different recipes");
						eq(Long.toString(sum), q.solutionPlain(), kind + ": solution");
					}
					case "enchantment" -> {
						List<MegaTrivia.Ench> list = (List<MegaTrivia.Ench>) q.facts().get("enchants");
						StringBuilder digits = new StringBuilder();
						StringBuilder spaced = new StringBuilder();
						for (MegaTrivia.Ench e : list) {
							eq(e.holder().value().definition().maxLevel(), e.maxLevel(), "enchantment: max level per definition");
							digits.append(e.holder().value().definition().maxLevel());
							spaced.append(spaced.isEmpty() ? "" : ", ").append(e.holder().value().definition().maxLevel());
						}
						eq(MegaTrivia.ENCHANTS, new HashSet<>(list).size(), "enchantment: six different");
						eq(digits.toString(), q.solutionPlain(), "enchantment: digit sequence");
						isTrue(q.digitSequence(), "enchantment: digit sequence question");
						isTrue(q.isCorrect(spaced.toString()), "enchantment: also with commas: " + spaced);
						isTrue(q.isCorrect(spaced.toString().replace(",", "")), "enchantment: also with spaces");
					}
					default -> {
						MegaTrivia.Smelt s = (MegaTrivia.Smelt) q.facts().get("smelt");
						var found = server.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(s.input())), level);
						isTrue(found.isPresent(), "smelting: furnace recipe for " + s.input());
						Item out = Mc.assemble(found.get().value(), new SingleRecipeInput(new ItemStack(s.input())), server.registryAccess()).getItem();
						eq(s.result(), out, "smelting: result per RecipeManager");
						isFalse(q.numeric(), "smelting: text answer");
						isTrue(q.isCorrect(MegaNames.english(s.result())), "smelting: English name");
						isTrue(q.isCorrect(dev.smpeconomy.core.Items.id(s.result())), "smelting: item id");
						isTrue(mentions(q, s.input().getDescriptionId()), "smelting: ingredient named in the question");
						isFalse(q.isCorrect("minecraft:air"), "smelting: nonsense is wrong");
						if (i < 100) {
							String answer = MegaNames.english(s.result());
							eq(MegaQuestion.ReadKind.UNCLEAR, q.read("is it " + answer + "?").kind(), "smelting: sentence with the solution stays private");
							for (MegaTrivia.Smelt o : d.smelts) {
								eq(MegaQuestion.ReadKind.ATTEMPT, q.read(MegaNames.english(o.result())).kind(), "smelting: every furnace result is an attempt");
							}
							eq(MegaQuestion.ReadKind.ATTEMPT, q.read("Diamond Sword").kind(), "smelting: known item name is an attempt");
						}
					}
				}
			}
		}
		ctx.detail(total + " trivia questions checked (" + d.byStack.values().stream().mapToInt(List::size).sum() + " items, " + d.mobs.size() + " mobs, "
			+ d.crafts.size() + " recipes, " + d.smelts.size() + " furnace recipes, " + d.enchants.size() + " enchantments)");
	}

	private static int createdHealth(EntityType<?> type, ServerLevel level) {
		Entity e = type.create(level, EntitySpawnReason.COMMAND);
		if (!(e instanceof LivingEntity le)) throw new AssertionError("not a living entity: " + type);
		int hp = Math.round(le.getMaxHealth());
		e.discard();
		return hp;
	}

	private static int[] displaySlotsAndCount(Recipe<?> recipe) {
		for (RecipeDisplay display : recipe.display()) {
			if (display instanceof ShapedCraftingRecipeDisplay s) {
				int slots = (int) s.ingredients().stream().filter(sd -> !(sd instanceof SlotDisplay.Empty)).count();
				return new int[] {slots, resultCount(s.result())};
			}
			if (display instanceof ShapelessCraftingRecipeDisplay s) return new int[] {s.ingredients().size(), resultCount(s.result())};
		}
		throw new AssertionError("no crafting display for " + recipe);
	}

	private static int resultCount(SlotDisplay d) {
		//? if >=26.1 {
		/*if (d instanceof SlotDisplay.ItemStackSlotDisplay s) return s.stack().count(); // ItemStackTemplate
		*///?} else {
		if (d instanceof SlotDisplay.ItemStackSlotDisplay s) return s.stack().getCount();
		//?}
		if (d instanceof SlotDisplay.ItemSlotDisplay) return 1;
		throw new AssertionError("unexpected result display " + d);
	}

	private static boolean mentions(MegaQuestion q, String key) {
		for (Component c : q.lines()) {
			if (mentions(c, key)) return true;
		}
		return false;
	}

	private static boolean mentions(Component c, String key) {
		if (c.getContents() instanceof TranslatableContents tc && tc.getKey().equals(key)) return true;
		for (Component s : c.getSiblings()) {
			if (mentions(s, key)) return true;
		}
		return false;
	}

	private static void words(TestContext ctx, MegaContent content) {
		isTrue(content.riddles.size() >= MegaContent.MIN_RIDDLES, "riddles present (" + content.riddles.size() + ")");
		Random r = new Random(7);
		Set<String> seenRiddles = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			MegaQuestion q = MegaWords.riddle(content, r);
			MegaContent.Riddle riddle = (MegaContent.Riddle) q.facts().get("riddle");
			seenRiddles.add(riddle.answers.get(0));
			for (String a : riddle.answers) isTrue(q.isCorrect(a), "riddle answer " + a);
			isTrue(q.isCorrect(riddle.answers.get(0).toUpperCase(Locale.ROOT)), "case ignored");
			isFalse(q.isCorrect("Diamond Sword"), "wrong riddle answer");
			eq(riddle.clues.size() - 1, q.hints().size(), "more hints");
			eq(MegaQuestion.ReadKind.ATTEMPT, q.read(riddle.answers.get(0)).kind(), "answer counts as an attempt");
			eq(MegaQuestion.ReadKind.CHAT, q.read("i have no idea what this is supposed to be honestly").kind(), "long sentence stays chat");
			eq(MegaQuestion.ReadKind.CHAT, q.read("gg").kind(), "short chat stays chat");
			eq(MegaQuestion.ReadKind.UNCLEAR, q.read("is it " + riddle.answers.get(0) + "?").kind(), "sentence with the solution stays private");
			// every riddle answer, not just the right one, is treated the same, so hiding reveals nothing
			for (MegaContent.Riddle other : content.riddles) {
				eq(MegaQuestion.ReadKind.UNCLEAR, q.read("i think " + other.answers.get(0) + " or so").kind(), "every riddle answer in a sentence is private");
			}
		}
		eq(content.riddles.size(), seenRiddles.size(), "all riddles occur");
		List<String> terms = MegaWords.terms(content, MegaNames.englishNames());
		isTrue(terms.size() >= MegaContent.MIN_TERMS + 10, "terms for the word scramble (" + terms.size() + ")");
		int checks = 0;
		for (String term : terms) {
			for (int k = 0; k < 10; k++, checks++) {
				String scrambled = MegaWords.scramble(term, r);
				List<String> orig = MegaWords.words(MegaWords.upper(term));
				List<String> mixed = MegaWords.words(scrambled);
				eq(orig.size(), mixed.size(), "same word count: " + term);
				for (int w = 0; w < orig.size(); w++) {
					eq(sorted(orig.get(w)), sorted(mixed.get(w)), "same letters per word: " + term);
					isFalse(orig.get(w).equals(mixed.get(w)), "every word scrambled: " + term + " → " + scrambled);
				}
			}
		}
		for (int i = 0; i < 500; i++) {
			MegaQuestion q = MegaWords.unscramble(terms, r);
			String term = (String) q.facts().get("term");
			isTrue(q.isCorrect(term), "word scramble solution: " + term);
			isTrue(q.isCorrect(term.toLowerCase(Locale.ROOT).replace('-', ' ')), "lower case + no hyphen: " + term);
			isFalse(q.isCorrect((String) q.facts().get("scrambled")), "scrambled is wrong");
			eq(3, q.hints().size(), "three hints");
			eq(MegaQuestion.ReadKind.UNCLEAR, q.read("is it " + term.toLowerCase(Locale.ROOT) + "?").kind(), "word scramble: sentence with the solution stays private");
			String other = terms.get(r.nextInt(terms.size()));
			eq(MegaQuestion.ReadKind.ATTEMPT, q.read(other).kind(), "word scramble: every term is an attempt");
		}
		ctx.detail(content.riddles.size() + " riddles, " + terms.size() + " terms, " + checks + " shuffles checked");
	}

	private static String sorted(String s) {
		char[] c = s.toCharArray();
		java.util.Arrays.sort(c);
		return new String(c);
	}

	private static void answerFormat(TestContext ctx) {
		eq("7200", MegaAnswers.number("7200"), "number");
		eq("7200", MegaAnswers.number("7.200"), "thousands dot");
		eq("7200", MegaAnswers.number("7,200"), "thousands comma");
		eq("1234567", MegaAnswers.number("1 234 567"), "space groups");
		eq("-5", MegaAnswers.number("-5"), "negative");
		eq("-5", MegaAnswers.number("−5"), "typographic minus");
		eq("-5", MegaAnswers.number("x = -5"), "x = …");
		eq("54", MegaAnswers.number("= 54"), "= …");
		eq("54", MegaAnswers.number("54 divisors"), "number with word");
		eq("54", MegaAnswers.number("54!"), "punctuation");
		eq("0", MegaAnswers.number("-0"), "minus zero");
		eq("12", MegaAnswers.number("012"), "leading zero");
		eq(null, MegaAnswers.number("12,5"), "decimal is not an integer");
		eq(null, MegaAnswers.number("1 9"), "two numbers");
		eq(null, MegaAnswers.number("54 or 55"), "several numbers");
		eq(null, MegaAnswers.number("hello"), "no value");
		eq("531", MegaAnswers.digitSequence("5 3 1"), "digit sequence with spaces");
		eq("531", MegaAnswers.digitSequence("5, 3 and 1"), "digit sequence with comma and the word and");
		eq("531", MegaAnswers.digitSequence("5,3,1"), "digit sequence with commas");
		eq(null, MegaAnswers.digitSequence("i think 531"), "sentence is no digit sequence");
		isTrue(MegaAnswers.hasDigit("５４") && !MegaAnswers.hasDigit("gg"), "full-width digits too");
		MegaQuestion num = MegaQuestion.builder(Type.MATH, "test", "Test").line("§f?").number(54).build();
		for (String t : List.of("54", "x = 54", "54 divisors", "= 54", "-7", "1.234")) eq(MegaQuestion.ReadKind.ATTEMPT, num.read(t).kind(), "attempt: " + t);
		for (String t : List.of("5 3 1", "5,3,1", "i think 54", "x=4 y=-3", "x1 = 7", "brb 5 min", "54 or 55", "５４", "there are 5 4"))
			eq(MegaQuestion.ReadKind.UNCLEAR, num.read(t).kind(), "private without scoring: " + t);
		for (String t : List.of("gg", "how does this work?", "am afk", "lol")) eq(MegaQuestion.ReadKind.CHAT, num.read(t).kind(), "chat without digit: " + t);
		MegaQuestion seq = MegaQuestion.builder(Type.TRIVIA, "test", "Test").line("§f?").digits("531").build();
		for (String t : List.of("531", "5 3 1", "5,3,1", "5, 3 and 1", "5-3-1")) isTrue(seq.isCorrect(t), "digit sequence correct: " + t);
		eq(MegaQuestion.ReadKind.UNCLEAR, seq.read("i think 531").kind(), "digit sequence in a sentence is private");
		MegaQuestion q = MegaQuestion.builder(Type.MATH, "test", "Test").line("§f?").number("-21").build();
		isTrue(q.isCorrect("-21") && q.isCorrect("−21") && q.isCorrect("x·y = -21".replace("x·y", "xy")), "negative solution in several spellings");
		isFalse(q.isCorrect("21"), "sign matters");
		MegaQuestion t = MegaQuestion.builder(Type.RIDDLE, "test", "Test").line("§f?").answers(List.of("Elder Guardian"))
			.guard(List.of("Echo Shard", "Warden", "Elder Guardian")).vocabulary(List.of("Stone", "Creeper")).build();
		isTrue(t.isCorrect("elder guardian") && t.isCorrect("ELDER GUARDIAN"), "text answers normalized");
		for (String m : List.of("elder guardian?", "warden", "Echo Shard", "stone", "creeper")) eq(MegaQuestion.ReadKind.ATTEMPT, t.read(m).kind(), "text attempt: " + m);
		for (String m : List.of("is it the echo shard?", "maybe the elder guardian", "echo shardd", "warden or so"))
			eq(MegaQuestion.ReadKind.UNCLEAR, t.read(m).kind(), "text private without scoring: " + m);
		for (String m : List.of("lol", "gg", "how does this work?", "am afk", "does anyone know where the next shop is", "i need stone"))
			eq(MegaQuestion.ReadKind.CHAT, t.read(m).kind(), "text chat stays public: " + m);
		ctx.detail("number/text normalization and attempt detection");
	}

	/**
	 * What appears publicly never depends on correctness: participant messages with digits stay private in any
	 * form; non-participants get the same for right and wrong; chat games off is always normal chat.
	 */
	private static void privacyNoOracle(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		FakePlayer late = ctx.player("MegaC");
		FakePlayer ex = ctx.player("MegaD");
		for (FakePlayer p : List.of(a, b, late, ex)) EconomyService.set(p.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {6_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID(), ex.getUUID()));
		env.excluded.put(ex.getUUID(), "only from 60 min playtime (you have 5m)");
		env.forced = Prepared.of(MegaQuestion.builder(Type.TRIVIA, "enchantment", "Enchantments").line("§fMax levels?").digits("531").build());
		MegaChallenge mega = engine(new MegaChallenge.Data(), cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.TRIVIA, null, false), "Start");
		isFalse(mega.participants().contains(ex.getUUID()), "excluded (playtime)");
		isTrue(rec.any("@" + ex.getUUID()) && rec.any("playtime"), "excluded player learns the reason");
		env.eligible.add(late.getUUID());
		now[0] = mega.answersOpenAt() + 1;
		// participant: any message with digits stays private: the solution in forms the parser doesn't understand
		// (don't count) and wrong attempts in clear form (count, then "no attempts left", still private)
		List<String> forms = List.of("i think 531", "x=5 y=31", "it is 5-3-1 or", "５３１", "5.3.1 maybe", "4 3 1", "4,3,1", "4, 3 and 1", "431?",
			"x=4 y=-3", "x1 = 7", "432");
		for (String t : forms) {
			GuessResult r = mega.guess(b.getUUID(), "MegaB", t);
			isTrue(r.suppress(), "participant: private: " + t + " (" + r.outcome() + ")");
			isTrue(r.outcome() != Outcome.WON, "participant: unclear form does not win: " + t);
			now[0] += 3_100;
		}
		isFalse(rec.anyPublic("531") || rec.anyPublic("431") || rec.anyPublic("5-3-1"), "none of it public");
		for (FakePlayer np : List.of(late, ex)) {
			now[0] += 10_001;
			GuessResult right = mega.guess(np.getUUID(), "x", "5 3 1");
			String noteRight = lastPrivate(rec, np);
			now[0] += 10_001;
			GuessResult wrong = mega.guess(np.getUUID(), "x", "4 3 1");
			String noteWrong = lastPrivate(rec, np);
			eq(right, wrong, "non-participant: same result for right/wrong");
			eq(noteRight, noteWrong, "non-participant: same hint for right/wrong");
			isTrue(right.suppress(), "non-participant: solution not public");
			eq(right, mega.guess(np.getUUID(), "x", "531"), "non-participant: clear form the same too");
		}
		isTrue(lastPrivate(rec, ex).contains("playtime"), "excluded player sees the reason");
		env.off.add(a.getUUID());
		eq(mega.guess(a.getUUID(), "MegaA", "531"), mega.guess(a.getUUID(), "MegaA", "432"), "chat games off: same");
		eq(Outcome.PASS, mega.guess(a.getUUID(), "MegaA", "531").outcome(), "chat games off: normal chat");
		env.off.remove(a.getUUID());
		eq(Outcome.NOT_PARTICIPANT, mega.guess(a.getUUID(), "MegaA", "531").outcome(), "out after toggling");
		eq(Phase.QUESTION, mega.phase(), "nobody won through an oracle");
		for (FakePlayer p : List.of(a, late, ex)) eq(0L, EconomyService.balance(p.getUUID()), "no money for " + p.getGameProfile().name());
		mega.stop("§7Test");

		env.forced = Prepared.of(MegaQuestion.builder(Type.RIDDLE, "riddle", "Riddle").line("§f?").answers(List.of("Echo Shard"))
			.guard(List.of("Echo Shard", "Elytra", "Trident")).build());
		eq(null, mega.start(Type.RIDDLE, null, false), "riddle");
		now[0] = mega.answersOpenAt() + 1;
		GuessResult sr = mega.guess(b.getUUID(), "MegaB", "is it the echo shard?");
		GuessResult sw = mega.guess(b.getUUID(), "MegaB", "is it the trident?");
		eq(Outcome.UNCLEAR, sr.outcome(), "sentence with correct solution: private without scoring");
		eq(sr, sw, "sentence with wrong possible solution: the same");
		FakePlayer late2 = ctx.player("MegaE");
		env.eligible.add(late2.getUUID());
		now[0] += 10_001;
		GuessResult lr = mega.guess(late2.getUUID(), "x", "echo shard");
		String nr = lastPrivate(rec, late2);
		now[0] += 10_001;
		GuessResult lw = mega.guess(late2.getUUID(), "x", "elytra");
		eq(nr, lastPrivate(rec, late2), "non-participant, text: same hint");
		eq(lr, lw, "non-participant, text: right and wrong the same");
		eq(Outcome.PASS, mega.guess(b.getUUID(), "MegaB", "gg wp").outcome(), "normal chat stays public");
		mega.stop("§7Test");
		ctx.detail(forms.size() + " number forms private, non-participants/excluded/chat games off without oracle, text sentences private");
	}

	/**
	 * The 4 most common solutions of each number kind cover under 2 % together, math without server data, trivia
	 * with the real registries. A player has one attempt; 4 stands for a player with three alts above the playtime
	 * limit.
	 */
	private static void answerSpace(TestContext ctx) {
		int k = 4;
		Random r = new Random(77);
		StringBuilder detail = new StringBuilder();
		List<String> bad = new ArrayList<>();
		long t0 = System.nanoTime();
		for (String kind : MegaMath.KINDS) {
			Map<String, Integer> counts = new HashMap<>();
			int n = 25_000;
			for (int i = 0; i < n; i++) counts.merge(MegaMath.create(kind, r).solutionPlain(), 1, Integer::sum);
			double cov = coverage(counts, n, k);
			if (cov >= 0.02) bad.add("math " + kind + ": " + pct(cov) + " (" + top(counts, k) + ")");
			detail.append(kind).append(' ').append(pct(cov)).append(", ");
		}
		MegaNames.clearCache();
		MegaTrivia.Data d = MegaTrivia.build(ctx.server());
		for (String kind : MegaTrivia.KINDS) {
			if (kind.equals("smelting")) continue;
			Map<String, Integer> counts = new HashMap<>();
			int n = 12_000;
			for (int i = 0; i < n; i++) counts.merge(MegaTrivia.create(kind, d, r).solutionPlain(), 1, Integer::sum);
			double cov = coverage(counts, n, k);
			if (cov >= 0.02) bad.add("trivia " + kind + ": " + pct(cov) + " (" + top(counts, k) + ")");
			detail.append(kind).append(' ').append(pct(cov)).append(", ");
		}
		isTrue(bad.isEmpty(), "the " + k + " most common solutions cover 2 % or more: " + bad + " , all: " + detail);
		ctx.detail("Top-" + k + "-coverage per kind: " + detail + "duration " + (System.nanoTime() - t0) / 1_000_000 + " ms");
	}

	private static double coverage(Map<String, Integer> counts, int n, int k) {
		return counts.values().stream().sorted(java.util.Comparator.reverseOrder()).limit(k).mapToInt(Integer::intValue).sum() / (double) n;
	}

	private static String top(Map<String, Integer> counts, int k) {
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(k).map(Map.Entry::getKey)
			.reduce((x, y) -> x + "/" + y).orElse("");
	}

	private static String pct(double v) {
		return String.format(Locale.ROOT, "%.2f%%", v * 100);
	}

	private static void hiddenDelay(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		EconomyService.set(a.getUUID(), 0);
		MegaConfig cfg = testConfig();
		cfg.minAnswerDelaySeconds = 12;
		cfg.maxAnswerDelaySeconds = 30;
		long[] now = {7_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.add(a.getUUID());
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge mega = engine(new MegaChallenge.Data(), cfg, now, rec, env);
		mega.onServerStarted();
		long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		Set<String> ruleLines = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			int before = rec.lines.size();
			eq(null, mega.start(Type.MATH, null, false), "start " + i);
			long delay = mega.answersOpenAt() - mega.questionAt();
			isTrue(delay >= 12_000 && delay <= 30_000, "wait 12-30 s: " + delay);
			min = Math.min(min, delay);
			max = Math.max(max, delay);
			for (String l : rec.lines.subList(before, rec.lines.size())) {
				if (l.contains("secret wait")) ruleLines.add(l);
			}
			mega.stop("§7Test");
		}
		isTrue(max - min > 12_000, "wait randomly distributed (" + min + "–" + max + " ms)");
		eq(1, ruleLines.size(), "rule line independent of the drawn wait: " + ruleLines);
		isTrue(ruleLines.iterator().next().contains("up to 30 s"), "rule line names only the upper bound: " + ruleLines);
		isFalse(rec.lines.stream().anyMatch(l -> l.contains("at least") || l.matches(".*\\b12 s\\b.*")), "lower bound/drawn wait never shown");
		eq(null, mega.start(Type.MATH, null, false), "Start");
		long open = mega.answersOpenAt();
		now[0] = mega.questionAt() + 5_000;
		eq(Outcome.TOO_EARLY, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "bot after 5 s: too early");
		isTrue(lastPrivate(rec, a).contains("used up") && !lastPrivate(rec, a).matches(".*\\d+ s.*"), "too early: used up, no remaining time");
		String sol = mega.solutionLine();
		isTrue(sol != null && sol.contains("opensIn=" + (open - now[0]) + "ms"), "RCON solution line names the unlock: " + sol);
		now[0] = open + 200;
		eq(Outcome.WON, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "after the unlock the correct answer wins");
		MegaChallenge.Payout p = mega.data().payouts.get(mega.data().payouts.size() - 1);
		eq(200L, p.msAfterOpen, "gap to unlock recorded (bot suspicion below 1.5 s goes to the log)");
		ctx.detail("wait " + min + "–" + max + " ms, only the upper bound shown; too-early answers cost an attempt; unlock gap recorded");
	}

	private static void participationRules(TestContext ctx) {
		UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), p3 = UUID.randomUUID(), p4 = UUID.randomUUID(), p5 = UUID.randomUUID();
		Map<UUID, String> ips = Map.of(p1, "9.9.9.9", p2, "::ffff:9.9.9.9", p3, "10.0.0.2", p4, "172.18.0.2", p5, "172.18.0.2");
		Map<UUID, Long> ticks = Map.of(p1, 200 * 1200L, p2, 500 * 1200L, p3, 5 * 1200L, p4, 100 * 1200L, p5, 100 * 1200L);
		Set<UUID> all = new LinkedHashSet<>(List.of(p1, p2, p3, p4, p5));
		Map<UUID, String> ex = MegaLive.exclusions(all, ips::get, ticks::get, 60, true);
		eq(Set.of(p1, p3), ex.keySet(), "excluded: second account of the same public IP and too little playtime");
		isTrue(ex.get(p1).contains("IP"), "reason IP: " + ex.get(p1));
		isTrue(ex.get(p3).contains("playtime"), "reason playtime: " + ex.get(p3));
		eq(Set.of(p3), MegaLive.exclusions(all, ips::get, ticks::get, 60, false).keySet(), "IP rule off");
		eq(Set.of(), MegaLive.exclusions(all, ips::get, ticks::get, 0, false).keySet(), "both rules off");
		eq(null, MegaLive.publicIp("172.18.0.2"), "Docker/tunnel address never counts");
		eq(null, MegaLive.publicIp("100.64.1.2"), "CGNAT never counts");
		eq(null, MegaLive.publicIp("<unknown>"), "no IP");
		eq("9.9.9.9", MegaLive.publicIp("::ffff:9.9.9.9"), "IPv4-mapped");

		FakePlayer a = ctx.player("MegaA");
		MegaConfig cfg = testConfig();
		long[] now = {8_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.add(a.getUUID());
		env.excluded.put(a.getUUID(), "only from 60 min playtime (you have 1m)");
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge mega = engine(new MegaChallenge.Data(), cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.MATH, null, false), "Start");
		eq(Phase.IDLE, mega.phase(), "nobody may take part: skipped");
		isTrue(rec.anyPublic("is cancelled") && rec.any("@" + a.getUUID()), "notice to all and reason to the player");
		ctx.detail("playtime minimum, one account per public IP (tunnel/LAN never equal), skipped without participants");
	}

	private static MegaQuestion fixedQuestion() {
		return MegaQuestion.builder(Type.MATH, "divisors", "Divisor Count").line("§fHow many positive divisors does §e7200§f have?").number(54)
			.explain("§77200 = 2⁵ · 3² · 5² → §a54").build();
	}

	private static void singleWinner(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {1_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID()));
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		mega.onServerStarted();
		mega.setKeyChanceOverride(() -> 1.0);
		long keys0 = CrateKeys.count(b.getUUID(), CrateKeys.EPIC);
		eq(null, mega.start(Type.MATH, null, true), "start with announcement");
		eq(Phase.ANNOUNCE, mega.phase(), "announcement");
		isTrue(rec.titles.stream().anyMatch(t -> t.startsWith("MEGA-CHALLENGE|") && t.contains("$10,000") && t.endsWith("ANNOUNCE")), "title with prize + sound");
		isTrue(rec.barShown && rec.bar.contains("starts in"), "bossbar countdown: " + rec.bar);
		isTrue(rec.anyPublic("No external help"), "announcement: No external help");
		isFalse(rec.any("7200"), "question still secret");
		eq(Outcome.PASS, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "no answers during the announcement");
		now[0] += 59_000;
		mega.tick();
		eq(Phase.ANNOUNCE, mega.phase(), "still announcing after 59 s");
		now[0] += 1_000;
		mega.tick();
		eq(Phase.QUESTION, mega.phase(), "the question after 60 s");
		isTrue(rec.anyPublic("7200"), "question in chat");
		eq(2, mega.participants().size(), "two participants");
		now[0] += 6_000;
		GuessResult wrong = mega.guess(a.getUUID(), "MegaA", "55");
		eq(Outcome.WRONG, wrong.outcome(), "wrong answer");
		isTrue(wrong.suppress(), "wrong answer stays private");
		isTrue(rec.lines.stream().anyMatch(l -> l.startsWith("@" + a.getUUID()) && l.contains("wrong")), "feedback only to A");
		isFalse(rec.anyPublic("55"), "wrong answer not public");
		GuessResult win = mega.guess(b.getUUID(), "MegaB", "54");
		eq(Outcome.WON, win.outcome(), "B wins");
		isTrue(win.suppress(), "correct answer not public");
		eq(1_000_000L, win.paid(), "$10,000 paid");
		isTrue(win.key(), "epic key at chance 1.0");
		eq(Phase.IDLE, mega.phase(), "challenge ended");
		now[0] += 3_100;
		GuessResult late = mega.guess(a.getUUID(), "MegaA", "54");
		eq(Outcome.PASS, late.outcome(), "no payout after the win");
		eq(1_000_000L, EconomyService.balance(b.getUUID()), "winner balance");
		eq(0L, EconomyService.balance(a.getUUID()), "second correct answer gets nothing");
		eq(keys0 + 1, CrateKeys.count(b.getUUID(), CrateKeys.EPIC), "key via CrateKeys");
		eq(1L, PlayerDataService.getCounter(b.getUUID(), MegaChallenge.WINS_COUNTER), "win counter");
		eq(1, data.payouts.size(), "exactly one payout recorded");
		eq(mega.id(), data.payouts.get(0).id, "payout with challenge id");
		eq(null, data.active, "no running challenge left in the file");
		isTrue(rec.titles.stream().anyMatch(t -> t.contains("wins") && t.endsWith("WIN")), "winner title to all");
		eq(1L, rec.count("won the MEGA-CHALLENGE"), "exactly one winner message");
		isFalse(rec.barShown, "bossbar gone");
		mega.tick();
		isTrue(rec.any("Solution") && rec.any("2⁵"), "explanation after the reveal");
		isTrue(env.postponedUntil >= now[0], "next chat game postponed");
		ctx.detail("announcement, question, exactly one winner, $10,000 + key, explanation");
	}

	private static void rateLimit(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		FakePlayer c = ctx.player("MegaC");
		FakePlayer d = ctx.player("MegaD");
		for (FakePlayer p : List.of(a, b, c, d)) EconomyService.set(p.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {2_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID(), d.getUUID()));
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.MATH, null, false), "start without announcement");
		eq(Phase.QUESTION, mega.phase(), "question immediately");
		long q0 = mega.questionAt();
		eq(q0 + 5_000, mega.answersOpenAt(), "unlock after the (here fixed) wait");
		env.eligible.add(c.getUUID());
		now[0] = q0 + 1_000;
		GuessResult early = mega.guess(a.getUUID(), "MegaA", "54");
		eq(Outcome.TOO_EARLY, early.outcome(), "correct answer before unlock does not count");
		isTrue(early.suppress(), "too-early answer stays private");
		String earlyA = lastPrivate(rec, a);
		GuessResult earlyB = mega.guess(b.getUUID(), "MegaB", "53");
		eq(Outcome.TOO_EARLY, earlyB.outcome(), "wrong answer before unlock");
		eq(earlyA, lastPrivate(rec, b), "too early: same message for right and wrong");
		isFalse(earlyA.matches(".*\\d+ s left.*"), "too early: no remaining seconds (wait stays secret): " + earlyA);
		isTrue(earlyA.contains("3 attempts left"), "too early: attempt used: " + earlyA);
		eq(Phase.QUESTION, mega.phase(), "keeps running");
		now[0] = q0 + 3_999;
		eq(Outcome.TOO_FAST, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "3.999 s after the last attempt: too fast");
		now[0] = q0 + 5_000;
		eq(Outcome.WRONG, mega.guess(a.getUUID(), "MegaA", "1").outcome(), "2nd attempt");
		now[0] += 2_999;
		eq(Outcome.TOO_FAST, mega.guess(a.getUUID(), "MegaA", "2").outcome(), "too fast after 2.999 s");
		now[0] += 1;
		eq(Outcome.WRONG, mega.guess(a.getUUID(), "MegaA", "2").outcome(), "3rd attempt after 3 s");
		now[0] += 3_000;
		eq(Outcome.WRONG, mega.guess(a.getUUID(), "MegaA", "3").outcome(), "4th attempt");
		now[0] += 3_000;
		eq(Outcome.NO_TRIES, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "after 4 attempts not even the correct one");
		for (String t : List.of("i think 54", "5 3 1", "5,3,1", "x=4 y=-3")) {
			GuessResult u = mega.guess(b.getUUID(), "MegaB", t);
			eq(Outcome.UNCLEAR, u.outcome(), "unclear form: " + t);
			isTrue(u.suppress(), "unclear form stays private: " + t);
		}
		isTrue(lastPrivate(rec, b).contains("Not counted"), "hint about the form");
		long before = rec.count("@" + c.getUUID());
		GuessResult nc = mega.guess(c.getUUID(), "MegaC", "54");
		GuessResult nw = mega.guess(c.getUUID(), "MegaC", "53");
		eq(Outcome.NOT_PARTICIPANT, nc.outcome(), "came online after the start");
		eq(nc, nw, "non-participant: right and wrong treated the same");
		isTrue(nc.suppress(), "does not reveal the solution in chat");
		eq(before + 1, rec.count("@" + c.getUUID()), "one hint, then 10 s quiet");
		eq(Outcome.PASS, mega.guess(b.getUUID(), "MegaB", "does anyone have iron for me").outcome(), "normal chat without number");
		env.off.add(b.getUUID());
		GuessResult offRight = mega.guess(b.getUUID(), "MegaB", "54");
		GuessResult offWrong = mega.guess(b.getUUID(), "MegaB", "53");
		eq(Outcome.PASS, offRight.outcome(), "chat games off: correct number is normal chat");
		eq(offRight, offWrong, "chat games off: right and wrong the same");
		isFalse(mega.participants().contains(b.getUUID()), "chat games off: out");
		env.off.remove(b.getUUID());
		eq(Outcome.NOT_PARTICIPANT, mega.guess(b.getUUID(), "MegaB", "54").outcome(), "on again: stays out (attempts do not restart)");
		eq(Outcome.WON, mega.guess(d.getUUID(), "MegaD", "54").outcome(), "D wins");
		for (FakePlayer p : List.of(a, b, c)) eq(0L, EconomyService.balance(p.getUUID()), p.getGameProfile().name() + " without money");
		eq(1_000_000L, EconomyService.balance(d.getUUID()), "D paid");

		env.forced = Prepared.of(MegaQuestion.builder(Type.RIDDLE, "riddle", "Riddle").line("§fWho am I?").answers(List.of("Warden"))
			.guard(List.of("Echo Shard", "Elytra", "Trident", "Totem of Undying", "Warden")).build());
		eq(null, mega.start(Type.RIDDLE, null, false), "riddle start");
		long r0 = mega.questionAt();
		now[0] = r0 + 5_000;
		for (String chat : List.of("does anyone know where the shop is", "gg", "lol", "how does this work?", "am afk")) {
			eq(Outcome.PASS, mega.guess(a.getUUID(), "MegaA", chat).outcome(), "normal chat: " + chat);
		}
		GuessResult sentence = mega.guess(a.getUUID(), "MegaA", "is it the warden?");
		eq(Outcome.UNCLEAR, sentence.outcome(), "sentence with possible solution: private, does not count");
		List<String> wrong = List.of("Echo Shard", "Elytra", "Trident");
		for (int i = 0; i < cfg.maxTextGuesses; i++) {
			now[0] = r0 + 5_000 + i * 3_000L;
			eq(Outcome.WRONG, mega.guess(a.getUUID(), "MegaA", wrong.get(i)).outcome(), "text attempt " + (i + 1));
		}
		now[0] += 3_000;
		eq(Outcome.NO_TRIES, mega.guess(a.getUUID(), "MegaA", "Warden").outcome(), "after " + cfg.maxTextGuesses + " text attempts, no more");
		eq(Outcome.WON, mega.guess(c.getUUID(), "MegaC", "warden").outcome(), "C (online at start this time) wins with the English name");
		eq(1_000_000L, EconomyService.balance(c.getUUID()), "C paid");
		ctx.detail("secret wait (too early = attempt gone), 3 s gap, 4 number/" + cfg.maxTextGuesses
			+ " text attempts, unclear forms private without scoring, non-participants and chat games off without oracle");
	}

	private static String lastPrivate(Rec rec, ServerPlayer p) {
		String prefix = "@" + p.getUUID() + " ";
		for (int i = rec.lines.size() - 1; i >= 0; i--) {
			if (rec.lines.get(i).startsWith(prefix)) return rec.lines.get(i).substring(prefix.length());
		}
		return "";
	}

	private static void persistence(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		EconomyService.set(a.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {3_000_000_000L};
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.add(a.getUUID());
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge first = engine(data, cfg, now, new Rec(), env);
		first.onServerStarted();
		eq(null, first.start(Type.MATH, null, false), "Start");
		Assert.notNull(data.active, "running challenge is in the file");
		String json = DataStore.gson().toJson(data);
		isFalse(json.contains("7200") || json.contains("\"54\""), "file holds no question/solution: " + json);
		long lastRun = data.lastRunAt;

		MegaChallenge.Data reloaded = DataStore.gson().fromJson(json, MegaChallenge.Data.class);
		reloaded.fixNulls();
		now[0] += 30_000;
		Rec rec2 = new Rec();
		MegaChallenge second = engine(reloaded, cfg, now, rec2, env);
		second.onServerStarted();
		eq(null, reloaded.active, "restart aborts the challenge");
		eq(Phase.IDLE, second.phase(), "nothing running");
		now[0] += 6_000;
		eq(Outcome.PASS, second.guess(a.getUUID(), "MegaA", "54").outcome(), "answer after the restart does not count");
		eq(0L, EconomyService.balance(a.getUUID()), "no payout after the abort");
		eq(0, reloaded.payouts.size(), "no payout recorded");
		eq(lastRun, reloaded.lastRunAt, "last start stays saved");
		eq(lastRun + cfg.intervalMinutes * 60_000L, second.nextAt(), "next one an interval after the last start");

		MegaChallenge.Data paid = new MegaChallenge.Data();
		paid.seq = 41;
		MegaChallenge.Payout old = new MegaChallenge.Payout();
		old.id = 42;
		old.winner = a.getUUID().toString();
		old.amount = 1_000_000;
		paid.payouts.add(old);
		MegaChallenge third = engine(paid, cfg, now, new Rec(), env);
		third.onServerStarted();
		eq(null, third.start(Type.MATH, null, false), "start with id 42");
		eq(42L, third.id(), "ID 42");
		now[0] += 6_000;
		third.guess(a.getUUID(), "MegaA", "54");
		eq(0L, EconomyService.balance(a.getUUID()), "recorded id is not paid again");
		eq(1, paid.payouts.size(), "no second payout recorded");
		eq(Phase.IDLE, third.phase(), "ended anyway");

		MegaChallenge.Data d4 = new MegaChallenge.Data();
		MegaChallenge fourth = engine(d4, cfg, now, new Rec(), env);
		fourth.onServerStarted();
		fourth.start(Type.MATH, null, false);
		now[0] += 6_000;
		eq(Outcome.WON, fourth.guess(a.getUUID(), "MegaA", "54").outcome(), "win");
		MegaChallenge.Data d5 = DataStore.gson().fromJson(DataStore.gson().toJson(d4), MegaChallenge.Data.class);
		d5.fixNulls();
		eq(1, d5.payouts.size(), "payout saved");
		isTrue(MegaChallenge.isPaid(d5, fourth.id()), "id recognized as paid");
		eq(null, d5.active, "no running challenge saved");
		eq(1_000_000L, EconomyService.balance(a.getUUID()), "paid once");
		ctx.detail("restart midway: abort without payment; recorded id never twice; no solution in the file");
	}

	private static void scheduling(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		MegaConfig cfg = testConfig();
		long t0 = 4_000_000_000L;
		long[] now = {t0};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.forced = Prepared.of(fixedQuestion());
		MinigamesConfig mc = new MinigamesConfig();
		ChatGameContent content = new ChatGameContent();
		ChatGames chat = new ChatGames(() -> mc.chatGames, () -> content, () -> now[0], new Random(1), new ChatGames.Announcer() {
			@Override
			public void broadcast(Component message) {}

			@Override
			public void send(UUID player, Component message) {}
		});
		env.chat = chat;
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		chat.setPause(mega::busy);

		long interval = cfg.intervalMinutes * 60_000L;
		eq(30L * 60_000L, interval, "default: every 30 minutes");
		mega.onServerStarted();
		eq(t0 + interval, mega.nextAt(), "no previous one: one interval after the start");
		data.lastRunAt = t0 - 5 * 60_000L;
		mega.onServerStarted();
		eq(t0 + interval - 5 * 60_000L, mega.nextAt(), "one interval after the last (survives restarts)");
		data.lastRunAt = t0 - interval + 60_000L;
		mega.onServerStarted();
		eq(t0 + cfg.minMinutesAfterStart * 60_000L, mega.nextAt(), "never right after the start");
		data.lastRunAt = 0;
		mega.onServerStarted();

		now[0] = mega.nextAt();
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "no MEGA-CHALLENGE without players");
		env.eligible.add(a.getUUID());
		chat.start(dev.smpeconomy.features.minigames.chat.ChatGame.Type.MATH);
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "waits for the running chat game");
		chat.stop(null);
		mega.tick();
		eq(Phase.ANNOUNCE, mega.phase(), "then the announcement starts");
		eq(now[0], data.lastRunAt, "start saved");
		eq(now[0] + interval, mega.nextAt(), "next one an interval later");
		chat.scheduleNext(now[0]);
		long due = chat.nextAt();
		now[0] = Math.max(due, now[0] + 60_000);
		mega.tick();
		eq(Phase.QUESTION, mega.phase(), "question running");
		chat.tick(1);
		isTrue(chat.active().isEmpty(), "no normal chat game during the MEGA-CHALLENGE");
		eq("A MEGA-CHALLENGE is already running.", mega.start(Type.MATH, null, false), "no second MEGA-CHALLENGE");
		now[0] = mega.endsAt();
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "Timeout");
		isTrue(rec.any("Time's up") && rec.any("54"), "timeout with solution");
		isTrue(chat.nextAt() >= now[0] + cfg.chatGamePauseMinutes * 60_000L, "next chat game no earlier than after the pause");
		chat.tick(1);
		isTrue(chat.active().isEmpty(), "no chat game right after");
		now[0] = chat.nextAt();
		chat.tick(1);
		isTrue(chat.active().isPresent(), "chat games again after the pause");
		eq(null, mega.start(Type.MATH, null, true), "Admin-Start");
		isTrue(chat.active().isEmpty(), "running chat game ended for the MEGA-CHALLENGE");
		mega.stop("§7Test");
		cfg.enabled = false;
		now[0] = mega.nextAt() + 1;
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "disabled: no automatic MEGA-CHALLENGE");
		ctx.detail("every 30 min after start/last, waits for players and chat game, pauses chat games");
	}

	private static MegaConfig.Race race(String kind, String target, int amount) {
		return new MegaConfig.Race("test_" + kind, "Test " + kind, kind, target, amount, 10, 1);
	}

	private static void raceProbe(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		Map<UUID, ServerPlayer> online = new HashMap<>();
		online.put(a.getUUID(), a);
		online.put(b.getUUID(), b);
		MegaRaceProbe probe = new MegaRaceProbe(online::get);
		Set<UUID> both = Set.of(a.getUUID(), b.getUUID());
		Map<Stat<?>, Map<FakePlayer, Integer>> restore = new HashMap<>();

		Stat<?> crafted = Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
		remember(restore, crafted, a, b);
		probe.begin(race("craft", "minecraft:diamond_pickaxe", 1), both);
		eq(0L, probe.progress(a.getUUID()), "craft: start 0");
		a.getStats().setValue(a, crafted, a.getStats().getValue(crafted) + 1);
		eq(1L, probe.progress(a.getUUID()), "craft: +1 crafted");
		eq(0L, probe.progress(b.getUUID()), "craft: B unchanged");
		eq(-1L, probe.progress(UUID.randomUUID()), "non-participant");

		// Like in game, the vanilla stat rises on every kill with credit; simulated here.
		ServerLevel overworld = ctx.overworld();
		ServerLevel end = ctx.server().getLevel(Level.END);
		Stat<?> killedEnderman = Stats.ENTITY_KILLED.get(EntityType.ENDERMAN);
		Stat<?> killedZombie = Stats.ENTITY_KILLED.get(EntityType.ZOMBIE);
		remember(restore, killedEnderman, a, b);
		remember(restore, killedZombie, a, b);
		probe.begin(race("kill", "minecraft:enderman", 3).in("minecraft:overworld"), both);
		kill(probe, b, EntityType.ENDERMAN, overworld);
		kill(probe, b, EntityType.ENDERMAN, overworld);
		eq(2L, probe.progress(b.getUUID()), "kill: 2 endermen in the overworld");
		if (end != null) {
			kill(probe, b, EntityType.ENDERMAN, end);
			eq(2L, probe.progress(b.getUUID()), "kill: enderman in the End (farm) does not count although the stat rises");
		}
		kill(probe, b, EntityType.ZOMBIE, overworld);
		eq(2L, probe.progress(b.getUUID()), "kill: other mob does not count");
		probe.onKill(null, EntityType.ENDERMAN, overworld);
		eq(2L, probe.progress(b.getUUID()), "kill: nothing counts without kill credit");
		kill(probe, b, EntityType.ENDERMAN, overworld);
		eq(3L, probe.progress(b.getUUID()), "kill: 3 endermen");
		probe.begin(race("kill", MegaConfig.CATEGORY_PREFIX + "monster", 5).in("minecraft:overworld"), both);
		kill(probe, a, EntityType.ZOMBIE, overworld);
		kill(probe, a, EntityType.ENDERMAN, overworld);
		eq(2L, probe.progress(a.getUUID()), "kill: category:monster counts zombie and enderman");
		Stat<?> killedCow = Stats.ENTITY_KILLED.get(EntityType.COW);
		remember(restore, killedCow, a);
		kill(probe, a, EntityType.COW, overworld);
		eq(2L, probe.progress(a.getUUID()), "kill: cow is not hostile");

		Stat<?> fish = Stats.CUSTOM.get(Stats.FISH_CAUGHT);
		remember(restore, fish, a, b);
		probe.begin(race("stat", "minecraft:fish_caught", 5), both);
		a.getStats().setValue(a, fish, a.getStats().getValue(fish) + 2);
		eq(2L, probe.progress(a.getUUID()), "stat: 2 fish");
		Stat<?> sprint = Stats.CUSTOM.get(Stats.SPRINT_ONE_CM);
		remember(restore, sprint, a, b);
		bump(a, sprint, 123_456);
		probe.begin(race("stat", "minecraft:sprint_one_cm", 1000).scaled(100), both);
		eq(0L, probe.progress(a.getUUID()), "sprint: distance before the start does not count");
		bump(a, sprint, 25_050);
		eq(250L, probe.progress(a.getUUID()), "sprint: 250.5 blocks gives 250");

		a.setPos(0.5, 100, 0.5);
		a.setYRot(0);
		a.setXRot(0);
		probe.begin(race("stat", "minecraft:fish_caught", 5), both);
		long t = 1_000_000L;
		probe.sample(t);
		isFalse(probe.canWin(a.getUUID(), t), "active: not without movement since the start");
		a.getStats().setValue(a, fish, a.getStats().getValue(fish) + 5);
		probe.sample(t + 250);
		isFalse(probe.canWin(a.getUUID(), t + 250), "active: catches without movement (AFK farm) are not enough");
		a.setYRot(35);
		probe.sample(t + 500);
		isTrue(probe.canWin(a.getUUID(), t + 500), "active: after looking around");
		probe.sample(t + 500 + MegaRaceProbe.ACTIVE_WINDOW_MS);
		isTrue(probe.canWin(a.getUUID(), t + 500 + MegaRaceProbe.ACTIVE_WINDOW_MS), "active: still within a minute");
		probe.sample(t + 501 + MegaRaceProbe.ACTIVE_WINDOW_MS);
		isFalse(probe.canWin(a.getUUID(), t + 501 + MegaRaceProbe.ACTIVE_WINDOW_MS), "active: no longer after a minute without movement");
		a.setPos(3.5, 100, 0.5);
		probe.sample(t + 600 + MegaRaceProbe.ACTIVE_WINDOW_MS);
		isTrue(probe.canWin(a.getUUID(), t + 600 + MegaRaceProbe.ACTIVE_WINDOW_MS), "active: again after movement");

		ServerLevel level = ctx.overworld();
		BlockPos natural = new BlockPos(29_000_001, -60, 29_000_001);
		BlockPos placed = new BlockPos(29_000_002, -60, 29_000_001);
		PlacedBlockTracker.markPlaced(level, placed);
		ctx.cleanup(() -> PlacedBlockTracker.unmark(level, placed));
		// Like in game, "mined" rises on every break with the right tool, even for placed blocks; simulated here.
		Stat<?> minedDeepslate = Stats.BLOCK_MINED.get(Blocks.DEEPSLATE);
		Stat<?> minedStone = Stats.BLOCK_MINED.get(Blocks.STONE);
		remember(restore, minedDeepslate, a, b);
		remember(restore, minedStone, a, b);
		probe.begin(race("mine", "minecraft:deepslate,minecraft:stone", 3), both);
		a.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
		mine(probe, a, level, natural, Blocks.DEEPSLATE);
		mine(probe, a, level, natural, Blocks.STONE);
		eq(2L, probe.progress(a.getUUID()), "mine: two natural target blocks");
		mine(probe, a, level, placed, Blocks.DEEPSLATE);
		eq(2L, probe.progress(a.getUUID()), "mine: placed block does not count although the stat rises");
		bump(a, minedStone, 5);
		eq(2L, probe.progress(a.getUUID()), "mine: stat without observed natural mining (Veinminer follow-up blocks) does not count");
		Stat<?> minedIron = Stats.BLOCK_MINED.get(Blocks.IRON_ORE);
		remember(restore, minedIron, a);
		mine(probe, a, level, natural, Blocks.IRON_ORE);
		eq(2L, probe.progress(a.getUUID()), "mine: other block does not count");
		probe.onBlockBroken(b, level, natural, Blocks.DEEPSLATE.defaultBlockState());
		eq(0L, probe.progress(b.getUUID()), "mine: nothing counts without a pickaxe");
		a.setGameMode(GameType.CREATIVE);
		ctx.cleanup(() -> a.setGameMode(GameType.SURVIVAL));
		probe.onBlockBroken(a, level, natural, Blocks.DEEPSLATE.defaultBlockState());
		eq(2L, probe.progress(a.getUUID()), "mine: creative does not count");
		a.setYRot(a.getYRot() + 90);
		probe.sample(5_000L);
		isFalse(probe.canWin(a.getUUID(), 5_000L), "creative cannot win");
		a.setGameMode(GameType.SURVIVAL);
		isTrue(probe.canWin(a.getUUID(), 5_000L), "survival (active) can win");
		a.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

		ServerLevel nether = ctx.server().getLevel(Level.NETHER);
		if (nether != null) {
			probe.begin(race("portal", "minecraft:the_nether", 1), both);
			probe.onDimensionChange(a, level, nether);
			eq(0L, probe.progress(a.getUUID()), "portal: teleport without portal does not count");
			a.portalProcess = new PortalProcessor((NetherPortalBlock) Blocks.NETHER_PORTAL, BlockPos.ZERO);
			a.setPortalCooldown();
			probe.onDimensionChange(a, level, nether);
			eq(1L, probe.progress(a.getUUID()), "portal: through the portal into the Nether");
			a.portalProcess = null;
			a.setPortalCooldown(0);
			probe.onDimensionChange(b, nether, level);
			eq(0L, probe.progress(b.getUUID()), "portal: wrong direction does not count");
		}
		probe.end();
		eq(-1L, probe.progress(a.getUUID()), "no measuring after the end");
		for (Map.Entry<Stat<?>, Map<FakePlayer, Integer>> e : restore.entrySet()) {
			for (Map.Entry<FakePlayer, Integer> pe : e.getValue().entrySet()) pe.getKey().getStats().setValue(pe.getKey(), e.getKey(), pe.getValue());
		}
		ctx.detail("craft, kill (stat delta and credit, mob, category, overworld), stat (+scale), mine (stat delta and natural, tool),"
			+ " portal, active players only, creative excluded");
	}

	private static void bump(FakePlayer p, Stat<?> stat, int by) {
		p.getStats().setValue(p, stat, p.getStats().getValue(stat) + by);
	}

	private static void mine(MegaRaceProbe probe, FakePlayer p, ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
		bump(p, Stats.BLOCK_MINED.get(block), 1);
		probe.onBlockBroken(p, level, pos, block.defaultBlockState());
	}

	private static void kill(MegaRaceProbe probe, FakePlayer p, EntityType<?> type, Level level) {
		bump(p, Stats.ENTITY_KILLED.get(type), 1);
		probe.onKill(p, type, level);
	}

	private static void remember(Map<Stat<?>, Map<FakePlayer, Integer>> restore, Stat<?> stat, FakePlayer... players) {
		Map<FakePlayer, Integer> m = restore.computeIfAbsent(stat, k -> new HashMap<>());
		for (FakePlayer p : players) m.putIfAbsent(p, p.getStats().getValue(stat));
	}

	private static void raceWinner(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {5_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID()));
		Map<UUID, Long> progress = new HashMap<>();
		Set<UUID> blocked = new HashSet<>();
		env.probe = new MegaChallenge.RaceProbe() {
			@Override
			public void begin(MegaConfig.Race race, Set<UUID> participants) {
				progress.clear();
			}

			@Override
			public long progress(UUID player) {
				return progress.getOrDefault(player, 0L);
			}

			@Override
			public void sample(long now) {}

			@Override
			public boolean canWin(UUID player, long now) {
				return !blocked.contains(player);
			}

			@Override
			public void end() {}
		};
		MegaConfig.Race race = race("craft", "minecraft:cake", 1);
		env.forced = Prepared.of(race);
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.RACE, null, false), "race without announcement");
		eq(Phase.RACE, mega.phase(), "race running");
		eq(now[0] + 10 * 60_000L, mega.endsAt(), "race time limit");
		eq(Outcome.PASS, mega.guess(a.getUUID(), "MegaA", "1").outcome(), "in a race, chat is normal chat");
		eq(2, mega.participants().size(), "two participants");
		mega.raceTick();
		eq(Phase.RACE, mega.phase(), "no winner without progress");
		blocked.add(a.getUUID());
		progress.put(a.getUUID(), 1L);
		mega.raceTick();
		eq(Phase.RACE, mega.phase(), "creative/offline does not win");
		blocked.clear();
		progress.put(b.getUUID(), 2L);
		mega.raceTick();
		eq(Phase.IDLE, mega.phase(), "goal reached: end");
		isTrue(rec.any("only players who move"), "race rule: AFK does not win");
		eq(1_000_000L, EconomyService.balance(b.getUUID()), "B (largest progress in the same tick) wins");
		eq(0L, EconomyService.balance(a.getUUID()), "only one winner");
		mega.raceTick();
		eq(1, data.payouts.size(), "exactly one payout");
		env.forced = Prepared.of(race);
		eq(null, mega.start(Type.RACE, null, false), "second race");
		now[0] = mega.endsAt();
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "race timeout");
		eq(1, data.payouts.size(), "timeout pays nothing");
		ctx.detail("first to reach the goal wins, creative/offline excluded, timeout without payment");
	}

	private static void configDefaults(TestContext ctx) {
		MegaConfig c = new MegaConfig();
		MegaConfig.validate(c);
		eq(30, c.intervalMinutes, "every 30 minutes");
		eq(1_000_000L, c.reward, "$10,000");
		eq(2_000_000L, c.bonusReward, "BONUS question $20,000");
		isTrue(c.bonusChance > 0 && c.bonusChance <= 0.2, "BONUS question occasionally: " + c.bonusChance);
		eq(1, c.maxGuesses, "only the first attempt counts (number)");
		eq(1, c.maxTextGuesses, "only the first attempt counts (text)");
		eq(0, c.guessCooldownSeconds, "no 3 s gap (only the first attempt counts instead)");
		eq(Map.of("race", 50, "riddle", 20, "trivia", 15, "math", 15, "unscramble", 0), new HashMap<>(c.weights),
			"weights: race 50 %, riddle 20 %, trivia 15 %, math 15 %");
		isTrue(c.races.size() >= 4, "curated races (" + c.races.size() + ")");
		for (MegaConfig.Race r : c.races) eq(null, MegaConfig.targetProblem(r), "race target known: " + r.id);
		ServerLevel level = ctx.overworld();
		TagKey<net.minecraft.world.level.block.Block> ores = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));
		Set<String> movement = Set.of("minecraft:sprint_one_cm", "minecraft:walk_one_cm", "minecraft:swim_one_cm");
		boolean stone = false, sprint = false, night = false;
		for (MegaConfig.Race r : c.races) {
			isTrue(Set.of("mine", "kill", "stat").contains(r.kind), "stat races only (no crafting/portal): " + r.id + " " + r.kind);
			isTrue(r.label.matches("[\\x20-\\x7E]+"), "race text in English: " + r.label);
			switch (r.kind) {
				case "mine" -> {
					for (String part : r.target.split(",")) {
						isFalse(part.startsWith("#"), "no block tags as a default (groups): " + r.id);
						net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(part.trim()));
						var state = block.defaultBlockState();
						isFalse(state.is(ores) || state.is(BlockTags.LOGS) || state.is(BlockTags.MINEABLE_WITH_SHOVEL), "not in a Veinminer group: " + part);
						List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state, level, BlockPos.ZERO, null, null,
							new ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
						isFalse(drops.isEmpty(), "drops something: " + part);
						for (ItemStack d : drops) isFalse(d.is(block.asItem()), "not placeable again (drops " + d.getItem() + "): " + part);
						if (block == Blocks.STONE) stone = true;
					}
				}
				case "kill" -> {
					eq("minecraft:overworld", r.dimension, "kill race only in the overworld: " + r.id);
					if (r.night) night = r.target.equals(MegaConfig.CATEGORY_PREFIX + "monster");
				}
				case "stat" -> {
					isTrue(movement.contains(r.target), "movement stats only (no AFK farm): " + r.id + " " + r.target);
					if (r.target.equals("minecraft:sprint_one_cm")) sprint = r.scale == 100;
				}
				default -> throw new AssertionError(r.kind);
			}
		}
		isTrue(stone && sprint && night, "default set: mine stone/deepslate, sprint distance (blocks), hostile mobs at night");
		MegaConfig obtain = new MegaConfig();
		obtain.races.add(new MegaConfig.Race("pearls", "x", "obtain", "minecraft:ender_pearl", 16, 15, 1));
		Assert.assertThrows(IllegalArgumentException.class, () -> MegaConfig.validate(obtain), "pickup race rejected");
		MegaConfig nightMine = new MegaConfig();
		nightMine.races.add(new MegaConfig.Race("nightstone", "x", "mine", "minecraft:stone", 5, 5, 1).atNight());
		Assert.assertThrows(IllegalArgumentException.class, () -> MegaConfig.validate(nightMine), "night only with kill");
		MegaConfig badCategory = new MegaConfig();
		badCategory.races.add(new MegaConfig.Race("kat", "x", "kill", MegaConfig.CATEGORY_PREFIX + "nonsense", 5, 5, 1));
		Assert.assertThrows(IllegalArgumentException.class, () -> MegaConfig.validate(badCategory), "unknown mob category rejected");
		eq(12, c.minAnswerDelaySeconds, "secret wait from 12 s");
		eq(30, c.maxAnswerDelaySeconds, "secret wait up to 30 s");
		isTrue(c.minPlaytimeMinutes > 0 && c.onePerIp, "participation rules on");
		MegaConfig bad = new MegaConfig();
		bad.races.add(new MegaConfig.Race("broken", "x", "craft", "minecraft:nonexistent", 1, 5, 1));
		Assert.assertThrows(IllegalArgumentException.class, () -> MegaConfig.validate(bad), "unknown item rejected");
		MegaConfig badW = new MegaConfig();
		badW.weights.put("nonsense", 1);
		Assert.assertThrows(IllegalArgumentException.class, () -> MegaConfig.validate(badW), "unknown kind rejected");
		eq(Type.RACE, Type.parse("race"), "type by id");
		eq(null, Type.parse("nonsense"), "unknown type");
		ctx.detail(c.races.size() + " races (stat deltas only, no Veinminer groups, not placeable), weights 50/20/15/15, 1 attempt");
	}

	/**
	 * Only the first attempt per player and question counts, further ones are ignored with a private hint, even a
	 * correct answer after a wrong or too-early one. Unclear forms and normal chat cost nothing.
	 */
	private static void firstAttemptOnly(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		FakePlayer c = ctx.player("MegaC");
		for (FakePlayer p : List.of(a, b, c)) EconomyService.set(p.getUUID(), 0);
		MegaConfig cfg = new MegaConfig();
		cfg.epicKeyChance = 0;
		cfg.bonusChance = 0;
		cfg.minAnswerDelaySeconds = 5;
		cfg.maxAnswerDelaySeconds = 5;
		long[] now = {9_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID(), c.getUUID()));
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge mega = engine(new MegaChallenge.Data(), cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.MATH, null, false), "Start");
		isTrue(rec.anyPublic("only your FIRST answer counts"), "rule line: only the first attempt counts");
		isTrue(rec.anyPublic("No external help"), "rule line: No external help");
		isTrue(rec.anyPublic("up to 5 s"), "rule line names the upper bound of the wait");
		long q0 = mega.questionAt();
		isTrue(mega.playerStatus(a.getUUID()).stream().anyMatch(l -> l.getString().contains("none yet")), "/chatgame mega: no answer yet");
		now[0] = q0 + 1_000;
		eq(Outcome.TOO_EARLY, mega.guess(a.getUUID(), "MegaA", "54").outcome(), "A: too early");
		isTrue(lastPrivate(rec, a).contains("used up"), "A: attempt used: " + lastPrivate(rec, a));
		now[0] = mega.answersOpenAt() + 100;
		GuessResult again = mega.guess(a.getUUID(), "MegaA", "54");
		eq(Outcome.NO_TRIES, again.outcome(), "A: second attempt (correct) is ignored");
		isTrue(again.suppress(), "A: ignored attempt stays private");
		isTrue(lastPrivate(rec, a).contains("Only your first answer counts"), "A: private hint: " + lastPrivate(rec, a));
		isTrue(mega.playerStatus(a.getUUID()).stream().anyMatch(l -> l.getString().contains("You have answered")), "/chatgame mega: answered");
		eq(Outcome.WRONG, mega.guess(b.getUUID(), "MegaB", "55").outcome(), "B: first attempt wrong");
		isTrue(lastPrivate(rec, b).contains("wrong") && lastPrivate(rec, b).contains("only your first answer counts"), "B: wrong + hint: " + lastPrivate(rec, b));
		eq(Outcome.NO_TRIES, mega.guess(b.getUUID(), "MegaB", "54").outcome(), "B: correct answer afterwards ignored");
		now[0] += 60_000;
		eq(Outcome.NO_TRIES, mega.guess(b.getUUID(), "MegaB", "54").outcome(), "B: also ignored a minute later");
		eq(Outcome.UNCLEAR, mega.guess(c.getUUID(), "MegaC", "i think 54").outcome(), "C: unclear form does not count");
		eq(Outcome.PASS, mega.guess(c.getUUID(), "MegaC", "gg").outcome(), "C: normal chat");
		eq(Outcome.WON, mega.guess(c.getUUID(), "MegaC", "54").outcome(), "C: first scored attempt correct");
		eq(List.of(0L, 0L, 1_000_000L), List.of(EconomyService.balance(a.getUUID()), EconomyService.balance(b.getUUID()), EconomyService.balance(c.getUUID())),
			"only C paid");
		isFalse(rec.anyPublic("55"), "attempts not public");

		env.forced = Prepared.of(MegaQuestion.builder(Type.RIDDLE, "riddle", "Riddle").line("§fWho am I?").answers(List.of("Warden"))
			.guard(List.of("Echo Shard", "Elytra", "Warden")).build());
		eq(null, mega.start(Type.RIDDLE, null, false), "riddle start");
		now[0] = mega.answersOpenAt() + 1;
		eq(Outcome.WRONG, mega.guess(a.getUUID(), "MegaA", "Echo Shard").outcome(), "text: first attempt wrong");
		eq(Outcome.NO_TRIES, mega.guess(a.getUUID(), "MegaA", "Warden").outcome(), "text: second attempt ignored");
		eq(Outcome.PASS, mega.guess(a.getUUID(), "MegaA", "too bad").outcome(), "text: normal chat stays public");
		eq(Outcome.WON, mega.guess(b.getUUID(), "MegaB", "warden").outcome(), "text: B wins with the first attempt");
		ctx.detail("too early/wrong = only attempt gone, further ones ignored privately; unclear forms/chat free; number and text");
	}

	private static void bonusQuestion(TestContext ctx) {
		FakePlayer a = ctx.player("MegaA");
		FakePlayer b = ctx.player("MegaB");
		EconomyService.set(a.getUUID(), 0);
		EconomyService.set(b.getUUID(), 0);
		MegaConfig cfg = testConfig();
		long[] now = {10_000_000_000L};
		Rec rec = new Rec();
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.eligible.addAll(List.of(a.getUUID(), b.getUUID()));
		env.forced = Prepared.of(fixedQuestion());
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		mega.onServerStarted();
		eq(null, mega.start(Type.MATH, null, true, true), "BONUS start with announcement");
		isTrue(mega.bonus(), "BONUS");
		eq(2_000_000L, mega.reward(), "$20,000");
		isTrue(rec.titles.stream().anyMatch(t -> t.startsWith("BONUS QUESTION|") && t.contains("$20,000") && t.endsWith("ANNOUNCE")), "BONUS title with $20,000: "
			+ rec.titles);
		isTrue(rec.anyPublic("BONUS QUESTION") && rec.anyPublic("$20,000"), "announcement names BONUS and $20,000");
		isTrue(rec.anyPublic("No external help"), "announcement: No external help");
		isTrue(rec.bar.contains("BONUS") && rec.bar.contains("$20,000"), "bossbar: " + rec.bar);
		isTrue(mega.statusLine().contains("bonus=true reward=2000000"), "status: " + mega.statusLine());
		now[0] += 60_000;
		mega.tick();
		eq(Phase.QUESTION, mega.phase(), "question");
		now[0] = mega.answersOpenAt() + 1;
		GuessResult win = mega.guess(b.getUUID(), "MegaB", "54");
		eq(Outcome.WON, win.outcome(), "B wins the BONUS question");
		eq(2_000_000L, win.paid(), "$20,000 paid");
		eq(2_000_000L, EconomyService.balance(b.getUUID()), "B: $20,000");
		MegaChallenge.Payout p = data.payouts.get(data.payouts.size() - 1);
		isTrue(p.bonus && p.amount == 2_000_000L, "payout recorded as BONUS");
		isTrue(rec.titles.stream().anyMatch(t -> t.startsWith("BONUS QUESTION|") && t.contains("$20,000") && t.endsWith("WIN")), "winner title");
		isFalse(mega.bonus(), "no BONUS active afterwards");
		eq("A BONUS challenge is always a question, never a race.", mega.start(Type.RACE, null, true, true), "BONUS never a race");
		eq(null, mega.start(Type.MATH, null, false), "normal start");
		isFalse(mega.bonus(), "no BONUS");
		eq(1_000_000L, mega.reward(), "$10,000");
		mega.stop("§7Test");
		cfg.bonusChance = 1.0;
		now[0] = mega.nextAt();
		mega.tick();
		isTrue(mega.phase() == Phase.ANNOUNCE && mega.bonus(), "scheduled with bonusChance 1: BONUS question");
		mega.stop("§7Test");
		cfg.bonusChance = 0;
		now[0] = mega.nextAt();
		mega.tick();
		isTrue(mega.phase() == Phase.ANNOUNCE && !mega.bonus() && mega.reward() == 1_000_000L, "scheduled with bonusChance 0: normal challenge");
		mega.stop("§7Test");
		env.forced = null;
		cfg.weights = new java.util.LinkedHashMap<>(Map.of("race", 100, "math", 1, "trivia", 0, "riddle", 0, "unscramble", 0));
		for (int i = 0; i < 30; i++) {
			eq(null, mega.start(null, null, false, true), "BONUS start " + i);
			eq(Type.MATH, mega.current().type(), "BONUS is a question, not a race");
			mega.stop("§7Test");
		}
		ctx.detail("BONUS: $20,000, title/announcement/bossbar, payout recorded, never a race, scheduled by bonusChance");
	}

	private static void nightRace(TestContext ctx) {
		MegaConfig cfg = testConfig();
		MegaConfig.Race night = cfg.races.stream().filter(r -> r.night).findFirst().orElseThrow();
		eq(0L, MegaRaceProbe.nightTicksLeft(6_000), "noon: no night");
		eq(10_000L, MegaRaceProbe.nightTicksLeft(13_000), "night start: 10,000 ticks");
		eq(1L, MegaRaceProbe.nightTicksLeft(22_999), "last night tick");
		eq(0L, MegaRaceProbe.nightTicksLeft(23_000), "morning");
		eq(10_000L, MegaRaceProbe.nightTicksLeft(24_000L * 7 + 13_000), "on every day");
		isFalse(MegaGenerator.raceAvailable(night, cfg, 0), "not selectable by day");
		isFalse(MegaGenerator.raceAvailable(night, cfg, (cfg.announceSeconds + MegaGenerator.MIN_NIGHT_RACE_SECONDS - 1) * 1000L), "too little night");
		isTrue(MegaGenerator.raceAvailable(night, cfg, (cfg.announceSeconds + MegaGenerator.MIN_NIGHT_RACE_SECONDS) * 1000L), "enough night");
		TestEnv env = new TestEnv();
		env.cfg = cfg;
		env.night = 0;
		Assert.assertThrows(IllegalStateException.class, () -> env.prepare(Type.RACE, night.id, new Random(1)), "night race rejected by day");
		Random r = new Random(3);
		for (int i = 0; i < 300; i++) isFalse(env.prepare(Type.RACE, null, r).race().night, "never a night race by day");
		env.night = 8 * 60_000L;
		boolean picked = false;
		for (int i = 0; i < 300 && !picked; i++) picked = env.prepare(Type.RACE, null, r).race().night;
		isTrue(picked, "the night race is chosen at night");

		FakePlayer a = ctx.player("MegaA");
		EconomyService.set(a.getUUID(), 0);
		long[] now = {11_000_000_000L};
		Rec rec = new Rec();
		env.eligible.add(a.getUUID());
		env.probe = new MegaChallenge.RaceProbe() {
			@Override
			public void begin(MegaConfig.Race race, Set<UUID> participants) {}

			@Override
			public long progress(UUID player) {
				return 0;
			}

			@Override
			public void sample(long t) {}

			@Override
			public boolean canWin(UUID player, long t) {
				return true;
			}

			@Override
			public void end() {}
		};
		env.forced = Prepared.of(night);
		MegaChallenge.Data data = new MegaChallenge.Data();
		MegaChallenge mega = engine(data, cfg, now, rec, env);
		mega.onServerStarted();
		env.night = 5 * 60_000L;
		eq(null, mega.start(Type.RACE, null, false), "night race");
		eq(Phase.RACE, mega.phase(), "running");
		eq(now[0] + 5 * 60_000L, mega.endsAt(), "time limit = remaining night (shorter than " + night.minutes + " min)");
		isTrue(rec.anyPublic("No external help"), "race: No external help");
		now[0] += 1_000;
		env.night = 0;
		mega.tick();
		eq(Phase.IDLE, mega.phase(), "sunrise ends the race");
		isTrue(rec.anyPublic("sun has risen"), "notice: sunrise");
		eq(0, data.payouts.size(), "no payout");
		env.night = 30_000L;
		eq(null, mega.start(Type.RACE, null, false), "start with night almost over");
		eq(Phase.IDLE, mega.phase(), "skipped");
		isTrue(rec.anyPublic("night is almost over"), "notice: night almost over");

		ServerLevel overworld = ctx.overworld();
		long saved = Mc.dayTime(overworld);
		ctx.cleanup(() -> Mc.setDayTime(overworld, saved));
		Map<UUID, ServerPlayer> online = new HashMap<>();
		online.put(a.getUUID(), a);
		MegaRaceProbe probe = new MegaRaceProbe(online::get);
		Stat<?> killedZombie = Stats.ENTITY_KILLED.get(EntityType.ZOMBIE);
		int zombies0 = a.getStats().getValue(killedZombie);
		ctx.cleanup(() -> a.getStats().setValue(a, killedZombie, zombies0));
		long dayBase = saved - Math.floorMod(saved, MegaRaceProbe.DAY_TICKS);
		probe.begin(night, Set.of(a.getUUID()));
		Mc.setDayTime(overworld, dayBase + 6_000);
		kill(probe, a, EntityType.ZOMBIE, overworld);
		eq(0L, probe.progress(a.getUUID()), "kill by day does not count");
		Mc.setDayTime(overworld, dayBase + 14_000);
		kill(probe, a, EntityType.ZOMBIE, overworld);
		eq(1L, probe.progress(a.getUUID()), "kill at night counts");
		probe.end();
		Mc.setDayTime(overworld, saved);
		ctx.detail("night 13000-23000, selectable only with enough night, time limit = remaining night, sunrise ends, kills only at night");
	}

	private static void configSchema() {
		MinigamesConfig def = new MinigamesConfig();
		MinigamesConfig.validate(def);
		eq(MinigamesConfig.DEFAULTS_VERSION, def.defaultsVersion, "defaults with current schema version");
		isFalse(def.defaultsMigrated, "fresh defaults are not migrated");
		eq(10, def.chatGames.maxIntervalMinutes, "code default chat games 10/10");
		MinigamesConfig old = DataStore.gson().fromJson("{\"chatGames\":{\"minIntervalMinutes\":10,\"maxIntervalMinutes\":20},\"coinflip\":{},\"treasure\":{}}",
			MinigamesConfig.class);
		eq(0, old.defaultsVersion, "file without defaultsVersion");
		old.megaChallenge.intervalMinutes = 120;
		eq(List.of(), MinigamesConfig.migrate(old), "no value changes");
		MinigamesConfig.validate(old);
		eq(MinigamesConfig.DEFAULTS_VERSION, old.defaultsVersion, "defaultsVersion set");
		eq(20, old.chatGames.maxIntervalMinutes, "own interval stays");
		eq(120, old.megaChallenge.intervalMinutes, "own MEGA interval stays");
		isFalse(DataStore.gson().toJson(old).contains("defaultsMigrated"), "flag does not end up in the file");
		MinigamesConfig custom = new MinigamesConfig();
		custom.megaChallenge.races = new ArrayList<>(List.of(new MegaConfig.Race("own", "Mine 10 Stone", "mine", "minecraft:stone", 10, 5, 1)));
		MinigamesConfig.validate(custom);
		eq(List.of("own"), custom.megaChallenge.races.stream().map(r -> r.id).toList(), "own races stay");
	}
}
