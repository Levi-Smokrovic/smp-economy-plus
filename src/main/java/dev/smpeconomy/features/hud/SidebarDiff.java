package dev.smpeconomy.features.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.smpeconomy.features.hud.HudLines.Line;

/**
 * Minimal changes between two sidebar states. Each {@link Line} has a fixed holder and score, so changed text is one
 * SetScore packet, a dropped line one ResetScore packet and a new line one SetScore packet. Unchanged lines are never
 * resent, even if a line above appears or disappears; the gap just stays empty.
 */
final class SidebarDiff {
	/** {@code text == null} removes the line. */
	record Op(Line line, String text) {
		boolean isRemove() {
			return text == null;
		}
	}

	private SidebarDiff() {}

	/** From {@code sent} (on the client) to {@code wanted}, in line order; null means empty. */
	static List<Op> diff(Map<Line, String> sent, Map<Line, String> wanted) {
		Map<Line, String> old = sent == null ? Map.of() : sent;
		Map<Line, String> now = wanted == null ? Map.of() : wanted;
		List<Op> ops = new ArrayList<>();
		for (Line line : Line.values()) {
			String before = old.get(line);
			String after = now.get(line);
			if (after == null) {
				if (before != null) ops.add(new Op(line, null));
			} else if (!Objects.equals(before, after)) {
				ops.add(new Op(line, after));
			}
		}
		return ops;
	}
}
