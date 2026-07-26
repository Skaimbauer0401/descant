package mcbot.client.path;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiPredicate;
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
			Predicate<BlockState> palette, BiPredicate<BlockPos, BlockState> accept) {
		List<BlockPos> found = findNearest(level, origin, radius, 1, 0, palette, accept);
		return found.isEmpty() ? null : found.get(0);
	}

	/** The {@code count} nearest matches, closest first, judged on the block alone. */
	public static List<BlockPos> findNearest(ClientLevel level, BlockPos origin, int radius, int count,
			int spacing, Predicate<BlockState> wanted) {
		return findNearest(level, origin, radius, count, spacing, wanted,
				(pos, state) -> wanted.test(state));
	}

	/**
	 * The {@code count} nearest matches, closest first.
	 *
	 * <p>One implementation serves both this and the single-result form above, which take the same
	 * pruning decisions in the same places. Two copies of that reasoning would be two copies to keep
	 * correct, and the single-block version is only the case where {@code count} is one.</p>
	 *
	 * @param count   how many to return at most; anything below one returns nothing
	 * @param spacing minimum distance between results, or {@code 0} to allow neighbours. Ore comes in
	 *                veins, so without this "the eight nearest iron_ore" is eight blocks of the same
	 *                vein — technically the answer, and useless for deciding where to go
	 */
	public static List<BlockPos> findNearest(ClientLevel level, BlockPos origin, int radius, int count,
			int spacing, Predicate<BlockState> palette, BiPredicate<BlockPos, BlockState> accept) {
		if (count <= 0) {
			return List.of();
		}
		int chunkRadius = (radius >> 4) + 1;
		int originChunkX = origin.getX() >> 4;
		int originChunkZ = origin.getZ() >> 4;
		double radiusSqr = (double) radius * radius;

		// A max-heap, so the head is the *worst* of the ones being kept. That is both the entry to
		// evict when a better one turns up and the cutoff for whether a candidate is worth testing at
		// all — one structure answering both questions.
		PriorityQueue<Hit> kept = new PriorityQueue<>(count,
				Comparator.comparingDouble(Hit::distanceSqr).reversed());

		// Expanding rings of chunks: once the quota is filled, chunks further out than the current
		// worst kept match cannot beat it, so the search stops early on the common case of nearby hits.
		for (int ring = 0; ring <= chunkRadius; ring++) {
			if (kept.size() >= count
					&& (double) (ring - 1) * 16.0 > Math.sqrt(kept.peek().distanceSqr())) {
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
					searchChunk(level, chunk, origin, radiusSqr, count, spacing, palette, accept, kept);
				}
			}
		}

		return kept.stream()
				.sorted(Comparator.comparingDouble(Hit::distanceSqr))
				.map(Hit::pos)
				.toList();
	}

	/** One accepted match and how far it is, squared — the ordering key, never square-rooted. */
	private record Hit(BlockPos pos, double distanceSqr) {
	}

	private static void searchChunk(ClientLevel level, LevelChunk chunk, BlockPos origin,
			double radiusSqr, int count, int spacing, Predicate<BlockState> palette,
			BiPredicate<BlockPos, BlockState> accept, PriorityQueue<Hit> kept) {
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		double spacingSqr = (double) spacing * spacing;

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
						if (distanceSqr > radiusSqr) {
							continue;
						}
						// Full already, and no closer than the worst we are holding: nothing to gain,
						// and skipping before accept.test keeps the expensive test off the hot path.
						if (kept.size() >= count && distanceSqr >= kept.peek().distanceSqr()) {
							continue;
						}
						if (crowds(kept, pos, spacingSqr) || !accept.test(pos, state)) {
							continue;
						}
						kept.add(new Hit(pos, distanceSqr));
						if (kept.size() > count) {
							kept.poll();
						}
					}
				}
			}
		}
	}

	/**
	 * Whether {@code pos} sits too close to a match already held.
	 *
	 * <p>Approximate on purpose. Evicting a kept match can leave the one it crowded out unrecorded, so
	 * the result is "one per cluster, roughly" rather than a guaranteed spread — which is all a report
	 * of where things are needs to be, and it costs one short scan instead of a second pass.</p>
	 */
	private static boolean crowds(PriorityQueue<Hit> kept, BlockPos pos, double spacingSqr) {
		if (spacingSqr <= 0.0) {
			return false;
		}
		for (Hit hit : kept) {
			if (hit.pos().distSqr(pos) < spacingSqr) {
				return true;
			}
		}
		return false;
	}
}
