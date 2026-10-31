package dev.smpeconomy.features.afk;

/**
 * Box of block coordinates (both corners inclusive) in one dimension. A player is inside when their foot block
 * ({@code blockPosition()}) is in the box; {@code heightPadding} extra blocks count on top for jumping.
 */
public record AfkZone(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public static AfkZone of(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
		return new AfkZone(AfkConfig.normalizeDimension(dimension),
			Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
			Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
	}

	public boolean contains(String dim, int x, int y, int z, int heightPadding) {
		if (!dimension.equals(dim)) return false;
		long top = (long) maxY + Math.max(0, heightPadding);
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= top;
	}

	public long sizeX() {
		return (long) maxX - minX + 1;
	}

	public long sizeY() {
		return (long) maxY - minY + 1;
	}

	public long sizeZ() {
		return (long) maxZ - minZ + 1;
	}

	/** {@code "5×2×5"}, width by height by depth, without height padding. */
	public String sizeLabel() {
		return sizeX() + "×" + sizeY() + "×" + sizeZ();
	}
}
