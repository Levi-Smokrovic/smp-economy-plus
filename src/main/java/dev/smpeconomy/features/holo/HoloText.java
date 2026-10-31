package dev.smpeconomy.features.holo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import dev.smpeconomy.core.Msg;

/**
 * Builds hologram text with no world or server access, so selftests can cover all of it. The layout always has the
 * same height so the hologram doesn't jump when it updates: title, separator, {@code entries} rows, separator, update
 * time.
 */
final class HoloText {
	static final String SEPARATOR = "§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";
	/** Minecraft names have at most 16 characters; anything longer is cache garbage. */
	static final int MAX_NAME = 16;
	static final String UNKNOWN = "Unknown";

	record Row(String name, String value) {}

	/**
	 * Sorted by {@code primary} descending, then {@code secondary} descending, then name. Only {@code primary > 0} is
	 * ranked.
	 */
	record Score(UUID id, String name, long primary, long secondary, String value) {
		Row row() {
			return new Row(name, value);
		}
	}

	private static final Comparator<Score> ORDER = Comparator.comparingLong(Score::primary).reversed()
		.thenComparing(Comparator.comparingLong(Score::secondary).reversed())
		.thenComparing(s -> s.name() == null ? "" : s.name(), String.CASE_INSENSITIVE_ORDER)
		.thenComparing(s -> s.id() == null ? "" : s.id().toString());

	private HoloText() {}

	static List<Score> rank(Collection<Score> scores, int limit) {
		List<Score> list = new ArrayList<>();
		if (scores != null) {
			for (Score s : scores) {
				if (s != null && s.primary() > 0) list.add(s);
			}
		}
		list.sort(ORDER);
		return list.size() > Math.max(0, limit) ? new ArrayList<>(list.subList(0, Math.max(0, limit))) : list;
	}

	/** Gold, silver, bronze, then a gray still readable on the dark background. */
	static String rankColor(int rank) {
		return switch (rank) {
			case 1 -> "§6";
			case 2 -> "§f";
			case 3 -> "§c";
			default -> "§7";
		};
	}

	/** Control characters are stripped because line breaks would wreck the layout. */
	static String cleanName(String raw) {
		String s = clean(raw);
		if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME);
		return s.isEmpty() ? UNKNOWN : s;
	}

	static String clean(String raw) {
		if (raw == null) return "";
		String s = raw.replaceAll("§.", "").replace("§", "");
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!Character.isISOControl(c)) sb.append(c);
		}
		return sb.toString().trim();
	}

	/**
	 * @param rows already sorted; rows past {@code entries} are ignored
	 * @param stand time of calculation, e.g. {@code "14:05"}; null or empty omits it
	 */
	static List<String> lines(HoloType type, List<Row> rows, int entries, String stand) {
		int n = Math.max(1, entries);
		List<String> lines = new ArrayList<>(n + 4);
		lines.add(type.title);
		lines.add(SEPARATOR);
		for (int rank = 1; rank <= n; rank++) {
			Row row = rows != null && rank - 1 < rows.size() ? rows.get(rank - 1) : null;
			lines.add(row == null ? placeholder(rank) : rankLine(type, rank, row));
		}
		lines.add(SEPARATOR);
		lines.add(stand == null || stand.isBlank() ? "§7Updating …" : "§7Updated: §f" + clean(stand));
		return lines;
	}

	/** {@code §6#1 §fSteve §8» §a$1.2M} */
	static String rankLine(HoloType type, int rank, Row row) {
		String nameColor = rank <= 3 ? "§f" : "§7";
		String value = clean(row.value());
		return rankColor(rank) + "#" + rank + " " + nameColor + cleanName(row.name()) + " §8» " + type.valueColor + (value.isEmpty() ? "-" : value);
	}

	static String placeholder(int rank) {
		return "§8#" + rank + " §7-";
	}

	static Component component(List<String> lines) {
		return Msg.legacy(String.join("\n", lines));
	}

	static String plain(List<String> lines) {
		List<String> out = new ArrayList<>(lines.size());
		for (String l : lines) out.add(l.replaceAll("§.", ""));
		return String.join("\n", out);
	}
}
