package dev.smpeconomy.core.test;

import java.util.ArrayList;
import dev.smpeconomy.core.DataStore;
import java.util.List;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class TestContext {
	private final MinecraftServer server;
	private final List<Runnable> cleanups = new ArrayList<>();
	private final long flushesAtStart = DataStore.flushCount();
	private final List<FakePlayer> players = new ArrayList<>();
	private String detail = "ok";

	TestContext(MinecraftServer server) {
		this.server = server;
	}

	public MinecraftServer server() {
		return server;
	}

	public ServerLevel overworld() {
		return server.overworld();
	}

	public FakePlayer player(String name) {
		FakePlayer p = TestPlayers.get(server, name);
		if (!players.contains(p)) players.add(p);
		return p;
	}

	/**
	 * Restores the treasury after the test. Every test that books through {@code Treasury}, even indirectly, calls this
	 * first.
	 */
	public void preserveTreasury() {
		cleanup(dev.smpeconomy.core.Treasury.snapshot());
	}

	/**
	 * What {@code Items.giveOrDrop} would have dropped for this test player, as copies. The entities are removed at
	 * once, so no real items land at spawn.
	 */
	public List<ItemStack> drops(FakePlayer player) {
		return TestPlayers.drops(player.getUUID());
	}

	/** Runs after the test, even on FAIL, in reverse order. */
	public void cleanup(Runnable action) {
		cleanups.add(action);
	}

	public void detail(String text) {
		this.detail = text;
	}

	String detail() {
		return detail;
	}

	List<String> runCleanups() {
		List<String> errors = new ArrayList<>();
		for (int i = cleanups.size() - 1; i >= 0; i--) {
			try {
				cleanups.get(i).run();
			} catch (Throwable t) {
				errors.add("cleanup: " + t);
			}
		}
		for (FakePlayer p : players) {
			try {
				TestPlayers.cleanup(p);
			} catch (Throwable t) {
				errors.add("cleanup " + p.getPlainTextName() + ": " + t);
			}
		}
		// If the test itself wrote to disk, test players would sit in the real files until the next flush, and a crash
		// in between would leave them there. So write once more.
		if (!players.isEmpty() && DataStore.flushCount() != flushesAtStart && DataStore.dataDir() != null) {
			try {
				DataStore.flushDirty();
			} catch (Throwable t) {
				errors.add("cleanup flush: " + t);
			}
		}
		return errors;
	}
}
