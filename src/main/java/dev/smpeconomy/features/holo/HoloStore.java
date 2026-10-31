package dev.smpeconomy.features.holo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;

/**
 * {@code <world>/smpeconomy/holos.json}: definitions only; {@link HoloManager} owns the entities per session. Server
 * thread only.
 */
final class HoloStore {
	static final String OVERWORLD = "minecraft:overworld";

	static final class Holo {
		int id;
		/**
		 * {@link HoloType#id}. Case differences are normalized on load. Unknown types from hand-edited files are kept
		 * but not shown.
		 */
		String type = "";
		/** Canonical with namespace ({@link #normalizeDimension}), e.g. {@code "minecraft:overworld"}. */
		String dimension = OVERWORLD;
		double x;
		double y;
		double z;
		String createdBy = "";
		long createdAt;

		Optional<HoloType> holoType() {
			return HoloType.byId(type);
		}

		/** Assumes the same dimension. */
		double distanceSqr(double px, double py, double pz) {
			double dx = x - px;
			double dy = y - py;
			double dz = z - pz;
			return dx * dx + dy * dy + dz * dz;
		}

		String where() {
			String dim = dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
			return dim + " " + coord(x) + ", " + coord(y) + ", " + coord(z);
		}

		String label() {
			return holoType().map(t -> t.label).orElse("?" + type);
		}
	}

	static final class Data {
		int nextId = 1;
		List<Holo> holos = new ArrayList<>();

		/** The new id has never been used before. */
		Holo add(HoloType type, String dimension, double x, double y, double z, String createdBy, long now) {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("invalid position");
			Holo h = new Holo();
			h.id = nextId++;
			h.type = type.id;
			h.dimension = normalizeDimension(dimension);
			h.x = round2(x);
			h.y = round2(y);
			h.z = round2(z);
			h.createdBy = createdBy == null ? "" : createdBy;
			h.createdAt = now;
			holos.add(h);
			return h;
		}

		Holo byId(int id) {
			for (Holo h : holos) {
				if (h.id == id) return h;
			}
			return null;
		}

		Holo remove(int id) {
			for (int i = 0; i < holos.size(); i++) {
				if (holos.get(i).id == id) return holos.remove(i);
			}
			return null;
		}

		/** Nearest hologram in the same dimension within {@code radius}; ties go to the smallest id. */
		Optional<Holo> nearest(String dimension, double x, double y, double z, double radius) {
			Holo best = null;
			double bestDist = Double.MAX_VALUE;
			double max = radius * radius;
			for (Holo h : holos) {
				if (!sameDimension(h.dimension, dimension)) continue;
				double d = h.distanceSqr(x, y, z);
				if (d > max) continue;
				if (best == null || d < bestDist || (d == bestDist && h.id < best.id)) {
					best = h;
					bestDist = d;
				}
			}
			return Optional.ofNullable(best);
		}

		/**
		 * Repairs a loaded or hand-edited file: drops null entries and invalid positions, fills missing fields,
		 * canonicalizes dimensions, reassigns duplicate or invalid ids and moves {@code nextId} past all ids.
		 *
		 * @return true if anything changed
		 */
		boolean repair() {
			boolean changed = false;
			if (holos == null) {
				holos = new ArrayList<>();
				changed = true;
			}
			int before = holos.size();
			holos.removeIf(h -> h == null || !Double.isFinite(h.x) || !Double.isFinite(h.y) || !Double.isFinite(h.z));
			if (holos.size() != before) {
				SmpEconomy.LOG.warn("[SMPEco] holos.json: removed {} invalid entries", before - holos.size());
				changed = true;
			}
			int max = 0;
			for (Holo h : holos) max = Math.max(max, h.id);
			if (nextId <= max) {
				nextId = max + 1;
				changed = true;
			}
			if (nextId < 1) {
				nextId = 1;
				changed = true;
			}
			Set<Integer> seen = new HashSet<>();
			for (Holo h : holos) {
				if (h.type == null) {
					h.type = "";
					changed = true;
				}
				// Same type in another case: store the canonical id.
				HoloType known = h.holoType().orElse(null);
				if (known != null && !known.id.equals(h.type)) {
					SmpEconomy.LOG.info("[SMPEco] holos.json: type '{}' of hologram #{} -> {}", h.type, h.id, known.id);
					h.type = known.id;
					changed = true;
				}
				String dim = normalizeDimension(h.dimension);
				if (!dim.equals(h.dimension)) {
					if (h.dimension != null && !h.dimension.isBlank()) SmpEconomy.LOG.info("[SMPEco] holos.json: dimension '{}' of hologram #{} -> {}", h.dimension, h.id, dim);
					h.dimension = dim;
					changed = true;
				}
				if (h.createdBy == null) {
					h.createdBy = "";
					changed = true;
				}
				if (h.id <= 0 || !seen.add(h.id)) {
					int old = h.id;
					h.id = nextId++;
					seen.add(h.id);
					SmpEconomy.LOG.warn("[SMPEco] holos.json: duplicate or invalid id {} -> {}", old, h.id);
					changed = true;
				}
				if (h.holoType().isEmpty()) SmpEconomy.LOG.warn("[SMPEco] holos.json: hologram #{} has unknown type '{}', not shown", h.id, h.type);
			}
			return changed;
		}
	}

	static final DataStore<Data> STORE = new DataStore<>("holos.json", Data.class, Data::new);

	static {
		STORE.onLoad(data -> {
			if (data.repair()) STORE.markDirty();
		});
	}

	private HoloStore() {}

	/** Forces store creation during mod init, before SERVER_STARTED loads it. */
	static void init() {}

	static Data data() {
		return STORE.get();
	}

	static void markDirty() {
		STORE.markDirty();
	}

	/**
	 * Canonical dimension id as in {@code level.dimension().identifier().toString()}: no namespace means
	 * {@code minecraft:}, surrounding whitespace is dropped, empty or null means the overworld. Invalid ids stay as
	 * they are; they are not shown and only cause a warning.
	 */
	static String normalizeDimension(String dimension) {
		if (dimension == null || dimension.isBlank()) return OVERWORLD;
		String s = dimension.strip();
		Identifier id = Identifier.tryParse(s);
		return id == null ? s : id.toString();
	}

	/** True even if one side is not canonical yet. */
	static boolean sameDimension(String a, String b) {
		return normalizeDimension(a).equals(normalizeDimension(b));
	}

	static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** {@code 12.0} gives "12", {@code 12.5} gives "12.5". */
	static String coord(double v) {
		double r = round2(v);
		if (r == Math.rint(r) && Math.abs(r) < 1e15) return Long.toString((long) r);
		String s = String.format(java.util.Locale.ROOT, "%.2f", r);
		return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
	}
}
