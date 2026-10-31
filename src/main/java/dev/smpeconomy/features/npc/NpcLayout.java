package dev.smpeconomy.features.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/** Pure math and parsing with no world, so selftests can call it directly. */
final class NpcLayout {
	/** Degrees the head may turn away from the body. */
	static final float MAX_HEAD_TURN = 70f;
	static final float MAX_PITCH = 40f;
	/** Same as a standing player. */
	static final double EYE = 1.62;

	private NpcLayout() {}

	/** Item with optional leather color: {@code "leather_chestplate#3FA9F5"}. */
	record ItemSpec(String id, int color) {
		static final int NONE = -1;
	}

	static ItemSpec parseItemSpec(String spec) {
		if (spec == null || spec.isBlank()) throw new IllegalArgumentException("empty item");
		String s = spec.trim();
		int hash = s.indexOf('#');
		if (hash < 0) return new ItemSpec(s, ItemSpec.NONE);
		String id = s.substring(0, hash).trim();
		String hex = s.substring(hash + 1).trim();
		if (id.isEmpty()) throw new IllegalArgumentException("empty item: " + spec);
		if (hex.length() != 6 || !hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
			throw new IllegalArgumentException("color must be #RRGGBB: " + spec);
		}
		return new ItemSpec(id, Integer.parseInt(hex, 16));
	}

	/** {@code "60101018"} to ARGB; 6 digits are opaque. */
	static int parseArgb(String hex) {
		if (hex == null) throw new IllegalArgumentException("plateBackground is missing");
		String h = hex.trim();
		if (h.startsWith("#")) h = h.substring(1);
		if (!(h.length() == 6 || h.length() == 8) || !h.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
			throw new IllegalArgumentException("plateBackground must have 6 or 8 hex digits");
		}
		long v = Long.parseLong(h, 16);
		if (h.length() == 6) v |= 0xFF000000L;
		return (int) v;
	}

	/**
	 * {@code "wide/efe"} overrides the texture with the vanilla default skin (no Mojang lookup), a player name gives
	 * a by-name profile (the client loads the skin), empty gives null.
	 */
	static Tag profileTag(String skin) {
		if (skin == null || skin.isBlank()) return null;
		if (NpcConfig.VANILLA_SKIN.matcher(skin).matches()) {
			int slash = skin.indexOf('/');
			CompoundTag t = new CompoundTag();
			t.putString("texture", "minecraft:entity/player/" + skin);
			t.putString("model", skin.substring(0, slash));
			return t;
		}
		return StringTag.valueOf(skin);
	}

	record Place(double x, double y, double z, float yaw) {}

	static Place npcPlace(NpcConfig.Npc n) {
		return new Place(n.x, n.y, n.z, n.yaw);
	}

	static Place textPlace(NpcConfig.Text t) {
		return new Place(t.x, t.y, t.z, 0f);
	}

	static String normalize(String dimension) {
		return dimension.contains(":") ? dimension : "minecraft:" + dimension;
	}

	/**
	 * Returns {yaw, pitch}. Yaw stays within {@link #MAX_HEAD_TURN} of {@code bodyYaw}, pitch within
	 * {@link #MAX_PITCH}.
	 */
	static float[] lookAt(double fromX, double fromEyeY, double fromZ, double toX, double toEyeY, double toZ, float bodyYaw) {
		double dx = toX - fromX, dy = toEyeY - fromEyeY, dz = toZ - fromZ;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = horiz < 1.0E-4 ? bodyYaw : (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		float rel = Mth.clamp(Mth.wrapDegrees(yaw - bodyYaw), -MAX_HEAD_TURN, MAX_HEAD_TURN);
		float pitch = (float) -(Mth.atan2(dy, Math.max(horiz, 1.0E-4)) * Mth.RAD_TO_DEG);
		return new float[] {Mth.wrapDegrees(bodyYaw + rel), Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH)};
	}
}
