package mcbot.client.path;

import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds the nearest block of a given kind in the loaded world.
 *
 * <p>A naive sweep of a 128-block cube is two million lookups — far too slow to run on the client
 * thread. This instead walks chunk <em>sections</em> and asks each one
 * {@link LevelChunkSection#maybeHas(Predicate)} first: a palette test that rejects an entire 16³
 * section in one comparison. Only sections that might contain the block are scanned in detail, so
 * hunting for diamond ore skips essentially the whole world for free.</p>
 *
 * <p>Chunks are visited nearest-first, so a distant match never delays finding a close one.</p>
 */
public final class BlockSearcher {

	private BlockSearcher() {
	}

	/**
	 * Nearest position matching {@code wanted} within {@code radius} blocks, or {@code null}.
	 *
	 * <p>Only loaded chunks are searched — the client simply has no data for anything else.</p>
	 */
	public static BlockPos findNearest(ClientLevel level, BlockPos origin, int radius,
			Predicate<BlockState> wanted) {
		return findNearest(level, origin, radius, wanted, (pos, state) -> wanted.test(state));
	}

	/**
	 * As {@link #findNearest(ClientLevel, BlockPos, int, Predicate)}, but the final accept test
	 * also sees the block's position — needed when the choice depends on the surroundings (is this
	 * block exposed at the surface? is it above the searcher?) and not the block alone.
	 *
	 * @param palette a cheap block-only test used to skip whole 16³ sections; must be implied by
	 *                {@code accept}, or matches will be missed
	 */
	public static BlockPos findNearest(ClientLevel level, BlockPos origin, int radius,
			Predicate<BlockState> palette, java.util.function.BiPredicate<BlockPos, BlockState> accept) {
		int chunkRadius = (radius >> 4) + 1;
		int originChunkX = origin.getX() >> 4;
		int originChunkZ = origin.getZ() >> 4;
		long radiusSqr = (long) radius * radius;

		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		// Expanding rings of chunks: once a match is found, chunks further out than the current
		// best cannot beat it, so the search stops early on the common case of a nearby hit.
		for (int ring = 0; ring <= chunkRadius; ring++) {
			if (best != null && (double) (ring - 1) * 16.0 > bestDistance) {
				break;
			}
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					// Only the outer edge of each ring is new.
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
						continue;
					}
					LevelChunk chunk = level.getChunkSource()
							.getChunk(originChunkX + dx, originChunkZ + dz, false);
					if (chunk == null) {
						continue;
					}

					BlockPos found = searchChunk(level, chunk, origin, radiusSqr, palette, accept,
							bestDistance);
					if (found != null) {
						double distance = Math.sqrt(found.distSqr(origin));
						if (distance < bestDistance) {
							bestDistance = distance;
							best = found;
						}
					}
				}
			}
		}
		return best;
	}

	private static BlockPos searchChunk(ClientLevel level, LevelChunk chunk, BlockPos origin,
			long radiusSqr, Predicate<BlockState> palette,
			java.util.function.BiPredicate<BlockPos, BlockState> accept, double bestDistance) {
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos best = null;
		double best2 = bestDistance;

		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();

		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			// The palette test: rejects a whole 16x16x16 section without touching a single block.
			if (section == null || section.hasOnlyAir() || !section.maybeHas(palette)) {
				continue;
			}
			int baseY = level.getSectionYFromSectionIndex(index) << 4;

			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!palette.test(state)) {
							continue;
						}
						BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
						double distanceSqr = pos.distSqr(origin);
						if (distanceSqr > radiusSqr || distanceSqr >= best2 * best2) {
							continue;
						}
						if (!accept.test(pos, state)) {
							continue;
						}
						best2 = Math.sqrt(distanceSqr);
						best = pos;
					}
				}
			}
		}
		return best;
	}
}
