package dev.smpeconomy.core.test;

import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.Items;

/**
 * Snapshot comparison for values derived from vanilla data: canonical lines condensed to {@code n=<count> h=<hash>} and
 * compared to the expected value of the running MC version. A new MC version deliberately has none: the test fails with
 * the actual value until a port adds it, after checking against the old version.
 */
public final class Snapshot {
	private Snapshot() {}

	public static String mcVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
	}

	/**
	 * Sorted because vanilla tables like {@code VillagerTrades.TRADES} are HashMaps with identity-hashed keys, whose
	 * order changes on each JVM start. String.hashCode is specified, so the hash is stable.
	 */
	public static String of(List<String> lines) {
		List<String> sorted = new java.util.ArrayList<>(lines);
		java.util.Collections.sort(sorted);
		return "n=" + sorted.size() + " h=" + Integer.toHexString(String.join("\n", sorted).hashCode());
	}

	/**
	 * Writes sorted lines to {@code logs/selftest-snapshots/<name>-<MC version>.txt}, overwriting, so a port can diff
	 * two MC versions line by line before adding a new expected value. Diagnostics only; write errors are ignored.
	 */
	public static void write(String name, List<String> lines) {
		try {
			List<String> sorted = new java.util.ArrayList<>(lines);
			java.util.Collections.sort(sorted);
			java.nio.file.Path dir = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("selftest-snapshots");
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.write(dir.resolve(name + "-" + mcVersion() + ".txt"), sorted, java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException | RuntimeException e) {
			dev.smpeconomy.SmpEconomy.LOG.debug("[SMPEco] Snapshot {} not written: {}", name, e.toString());
		}
	}

	public static String stack(ItemStack s) {
		if (s == null || s.isEmpty()) return "-";
		int patch = s.getComponentsPatch().size();
		return Items.id(s.getItem()) + "x" + s.getCount() + (patch == 0 ? "" : "+" + patch);
	}

	/** Without an expected value the test fails and names the actual value. */
	public static void check(String what, Map<String, String> expectedByVersion, String actual) {
		String version = mcVersion();
		String expected = expectedByVersion.get(version);
		if (expected == null) Assert.fail(what + ": no expected value for MC " + version + " , actual value " + actual);
		Assert.eq(expected, actual, what + " (MC " + version + ")");
	}
}
