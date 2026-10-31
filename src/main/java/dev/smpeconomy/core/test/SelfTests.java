package dev.smpeconomy.core.test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;

/**
 * {@code /smpeco selftest [feature]} runs synchronously on the server thread and logs {@code [SELFTEST] PASS|FAIL
 * feature/name: detail} and {@code [SELFTEST] SUMMARY passed=X failed=Y}. A test passes if it finishes without an
 * exception.
 *
 * <p>Only with JVM flag {@code -Dsmpeconomy.selftest=true}: a run blocks the server thread, consumes id counters and
 * deliberately logs errors. Without the flag neither the command nor the tests exist.
 */
public final class SelfTests {
	@FunctionalInterface
	public interface Test {
		void run(TestContext ctx) throws Throwable;
	}

	public record Result(String feature, String name, boolean passed, String detail) {
		public String line() {
			return "[SELFTEST] " + (passed ? "PASS " : "FAIL ") + feature + "/" + name + ": " + detail;
		}
	}

	public record Summary(int passed, int failed, List<Result> results) {
		public String line() {
			return "[SELFTEST] SUMMARY passed=" + passed + " failed=" + failed;
		}
	}

	private record Entry(String feature, String name, Test test) {}

	public static final boolean ENABLED = Boolean.getBoolean("smpeconomy.selftest");
	private static final List<Entry> TESTS = new ArrayList<>();
	/** Has DST so the DST cases apply. */
	public static final java.time.ZoneId TEST_ZONE = java.time.ZoneId.of("Europe/Zurich");

	private SelfTests() {}

	public static void register(String feature, String name, Test test) {
		if (ENABLED) TESTS.add(new Entry(feature, name, test));
	}

	public static Set<String> features() {
		Set<String> set = new LinkedHashSet<>();
		for (Entry e : TESTS) set.add(e.feature());
		return set;
	}

	public static Summary run(MinecraftServer server, String featureFilter, Consumer<String> lineSink) {
		List<Result> results = new ArrayList<>();
		int passed = 0;
		// Key rewards are always tested, even with crates off (own test: crate-keys-gated).
		Runnable restoreKeys = dev.smpeconomy.core.CrateKeys.overrideEnabledForTest(true);
		// Time tests (day rollover, DST changes on 03-29 and 10-25) are written for a fixed zone, regardless of
		// core.json.
		java.time.ZoneId zoneBefore = dev.smpeconomy.core.TimeUtil.zone();
		dev.smpeconomy.core.TimeUtil.setZone(TEST_ZONE);
		try {
		for (Entry e : List.copyOf(TESTS)) {
			if (featureFilter != null && !featureFilter.equals(e.feature())) continue;
			TestContext ctx = new TestContext(server);
			String error = null;
			try {
				e.test().run(ctx);
			} catch (Throwable t) {
				error = t instanceof AssertionError ? t.getMessage() : t.getClass().getSimpleName() + ": " + t.getMessage();
				if (!(t instanceof AssertionError)) SmpEconomy.LOG.error("[SELFTEST] {}/{} Exception", e.feature(), e.name(), t);
			}
			List<String> cleanupErrors = ctx.runCleanups();
			if (error == null && !cleanupErrors.isEmpty()) error = String.join("; ", cleanupErrors);
			Result r = new Result(e.feature(), e.name(), error == null, error == null ? ctx.detail() : error);
			if (r.passed()) passed++;
			results.add(r);
			lineSink.accept(r.line());
		}
		} finally {
			restoreKeys.run();
			dev.smpeconomy.core.TimeUtil.setZone(zoneBefore);
		}
		Summary summary = new Summary(passed, results.size() - passed, results);
		lineSink.accept(summary.line());
		return summary;
	}
}
