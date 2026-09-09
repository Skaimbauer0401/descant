package descant.client.memory;

import net.minecraft.core.BlockPos;

/**
 * Somewhere worth being able to get back to.
 *
 * <p>Coordinates alone are not a memory. The same three numbers mean different places in the
 * overworld and the nether, and different places again on another server — so a remembered spot
 * carries the world and dimension it belongs to, and {@link Places} never offers one from somewhere
 * else. A bot that walks confidently to your base's coordinates in the nether is worse than one that
 * admits it does not know where the base is.</p>
 *
 * @param name      what to call it, and what {@code recall} and {@code goto} look it up by. Unique
 *                  within a world
 * @param kind      what sort of place it is — {@code base}, {@code stronghold}, {@code nether
 *                  fortress}. Free text, because the useful categories are not knowable in advance
 * @param world     which save or server this belongs to; see {@link Places#worldKey}
 * @param dimension {@code overworld}, {@code the_nether}, {@code the_end}
 * @param noticedAt when it was recorded, as epoch millis — the only way to tell a place you set up
 *                  last week from one the bot walked past a minute ago
 * @param automatic whether the bot noticed this itself rather than being told. Kept because the two
 *                  deserve different treatment: a landmark the bot spotted is a guess about what it
 *                  was looking at, and a name you gave it is not
 */
public record Place(String name, String kind, String world, String dimension,
		int x, int y, int z, long noticedAt, boolean automatic) {

	public BlockPos pos() {
		return new BlockPos(x, y, z);
	}

	/** Distance from a position, or {@code -1} when it is not even in the same dimension. */
	public double distanceFrom(BlockPos from, String fromDimension) {
		if (!dimension.equals(fromDimension)) {
			return -1.0;
		}
		return Math.sqrt(pos().distSqr(from));
	}

	/** One line for a listing: what it is, where, and how far. */
	public String describe(BlockPos from, String fromDimension) {
		StringBuilder text = new StringBuilder(name);
		if (!kind.isEmpty() && !kind.equalsIgnoreCase(name)) {
			text.append(" (").append(kind).append(')');
		}
		text.append(" at ").append(x).append(", ").append(y).append(", ").append(z);

		double distance = distanceFrom(from, fromDimension);
		if (distance < 0.0) {
			text.append(" in ").append(dimension);
		} else {
			text.append(" — ").append(Math.round(distance)).append("m away");
		}
		return text.toString();
	}
}
