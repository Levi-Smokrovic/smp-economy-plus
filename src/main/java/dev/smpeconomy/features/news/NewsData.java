package dev.smpeconomy.features.news;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state ({@code <world>/smpeconomy/news.json}): running events, history, schedule. Price factors themselves live in
 * {@code price_modifiers.json} (core); on startup {@link NewsEngine#reconcile} aligns both, so a restart loses nothing and leaves
 * nothing stuck.
 */
public final class NewsData {
	/** Running events, market shocks also while fading out. */
	public List<NewsEntry> active = new ArrayList<>();
	public List<NewsEntry> history = new ArrayList<>();
	public long lastEventAt;
	/**
	 * Schedule ({@link NewsPlanner}): online time counted since the last automatic event (ms) and the interval drawn for it (0 =
	 * none yet).
	 */
	public long progressMs;
	public long targetMs;
	public long autoStarted;
	public long nextId = 1;
	/** Automatic events paused ({@code /news auto off}), kept across restarts. Admin commands keep working. */
	public boolean autoPaused;

	/** Repairs nulls from old or broken files. */
	void repair() {
		if (active == null) active = new ArrayList<>();
		if (history == null) history = new ArrayList<>();
		active.removeIf(e -> e == null || NewsEntry.Type.parse(e.type) == null);
		history.removeIf(e -> e == null || NewsEntry.Type.parse(e.type) == null);
		if (progressMs < 0 || targetMs < 0) {
			progressMs = 0;
			targetMs = 0;
		}
		if (autoStarted < 0) autoStarted = 0;
		for (NewsEntry e : active) e.repair();
		for (NewsEntry e : history) e.repair();
		long maxId = 0;
		for (NewsEntry e : active) maxId = Math.max(maxId, e.id);
		for (NewsEntry e : history) maxId = Math.max(maxId, e.id);
		if (nextId <= maxId) nextId = maxId + 1;
	}

	public static final class NewsEntry {
		public enum Type {
			CRASH, HYPE, FLASH_SALE, BULK_ORDER, AUCTION_FEVER;

			static Type parse(String s) {
				if (s == null) return null;
				for (Type t : values()) {
					if (t.name().equals(s)) return t;
				}
				return null;
			}

			boolean isShock() {
				return this == CRASH || this == HYPE;
			}
		}

		public long id;
		/** Same group means announced together (a market shock with 1-3 items). */
		public long group;
		public String type = Type.CRASH.name();
		public String item = "";
		/** All items with a price factor (main item + family), shocks only. */
		public List<String> members = new ArrayList<>();
		/** Shock: target factor. Flash sale: discount factor (0.7 = -30 %). */
		public double factor = 1.0;
		public String category = "";
		public String categoryName = "";
		public int amount;
		/**
		 * Bulk order: order id in orders ({@link dev.smpeconomy.core.Services.NpcOrders#isOpen}). The event ends once the order
		 * is done, cancelled or expired. 0 means unknown (older news.json) and the event expires by time.
		 */
		public long orderId;
		/** Effective percent change shown at start, e.g. -50, +80, flash sale -35. */
		public int shownPercent;
		public long startMs;
		/** End of main phase; shocks then fade out over {@link #fadeOutMs}. */
		public long endMs;
		public long fadeOutMs;
		public boolean manual;
		public boolean cancelled;
		/** End of main phase already announced, not again after a restart. */
		public boolean endNotified;

		Type kind() {
			Type t = Type.parse(type);
			return t == null ? Type.CRASH : t;
		}

		boolean running(long now) {
			return now < endMs;
		}

		boolean finished(long now) {
			return now >= endMs + (kind().isShock() ? Math.max(0, fadeOutMs) : 0);
		}

		NewsEntry copy() {
			NewsEntry c = new NewsEntry();
			c.id = id;
			c.group = group;
			c.type = type;
			c.item = item;
			c.members = new ArrayList<>(members);
			c.factor = factor;
			c.category = category;
			c.categoryName = categoryName;
			c.amount = amount;
			c.orderId = orderId;
			c.shownPercent = shownPercent;
			c.startMs = startMs;
			c.endMs = endMs;
			c.fadeOutMs = fadeOutMs;
			c.manual = manual;
			c.cancelled = cancelled;
			c.endNotified = endNotified;
			return c;
		}

		void repair() {
			if (item == null) item = "";
			if (members == null) members = new ArrayList<>();
			members.removeIf(m -> m == null);
			if (category == null) category = "";
			if (categoryName == null) categoryName = "";
			if (!(factor > 0) || Double.isInfinite(factor)) factor = 1.0;
			if (orderId < 0) orderId = 0;
		}
	}
}
