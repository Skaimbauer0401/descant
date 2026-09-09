package descant.client.memory;

import java.util.List;
import java.util.Map;

import descant.client.BotSettings;
import descant.client.path.BlockSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the bot notices while it is walking about.
 *
 * <p>The half of memory nobody has to ask for. Places named by hand are the ones you already knew
 * about; these are the ones you would otherwise walk past and never find again — the stronghold you
 * clipped the corner of while mining, the fortress on the far side of a lava sea.</p>
 *
 * <h2>One block, one conclusion</h2>
 *
 * <p>Each landmark is a block that only really occurs in one kind of place, so seeing it is as good
 * as seeing the structure. That keeps the whole thing to a lookup rather than a structure detector,
 * and it is why the list is short: {@code stone_bricks} would mean a stronghold and also mean
 * somebody's house, and a memory full of wrong guesses is worse than a thin one.</p>
 *
 * <p>The one judgement call is the spawner, which is a dungeon in the overworld and a fortress in
 * the nether. Both are worth knowing and they are told apart by which world they are in.</p>
 *
 * <h2>Cheap enough to run while travelling</h2>
 *
 * <p>One search per scan, not one per block type: {@link BlockSearcher} skips a whole 16³ section on
 * a palette check, so a single pass looking for "any landmark" costs about what looking for one of
 * them would. Between scans nothing happens at all.</p>
 */
public final class Landmarks {

	/**
	 * Blocks that give a place away, and what they say about it.
	 *
	 * <p>Ordered by how confident the conclusion is rather than alphabetically, so that reading this
	 * list is also reading the reasoning. Everything here is either generated-only or expensive enough
	 * that its presence marks somewhere deliberate.</p>
	 */
	private static final Map<Block, String> MEANS = Map.ofEntries(
			// Generated only, and the whole point of finding a stronghold.
			Map.entry(Blocks.END_PORTAL_FRAME, "stronghold"),
			Map.entry(Blocks.END_PORTAL, "end portal"),
			// Ancient cities: neither block occurs anywhere else, and one of them is unobtainable.
			Map.entry(Blocks.REINFORCED_DEEPSLATE, "ancient city"),
			Map.entry(Blocks.SCULK_CATALYST, "ancient city"),
			// Bastions. Gilded blackstone is generated-only and worth going back for.
			Map.entry(Blocks.GILDED_BLACKSTONE, "bastion"),
			// Trial chambers.
			Map.entry(Blocks.TRIAL_SPAWNER, "trial chamber"),
			Map.entry(Blocks.VAULT, "trial chamber"),
			// A village, near enough: a bell is generated at the meeting point and rarely placed.
			Map.entry(Blocks.BELL, "village"),
			// Portals, which are how you get anywhere and the easiest thing to lose.
			Map.entry(Blocks.NETHER_PORTAL, "nether portal"),
			// Somebody built something here — yours or another player's, but not natural.
			Map.entry(Blocks.BEACON, "beacon"),
			Map.entry(Blocks.LODESTONE, "lodestone"),
			Map.entry(Blocks.RESPAWN_ANCHOR, "respawn anchor"),
			Map.entry(Blocks.ENCHANTING_TABLE, "enchanting table"));

	/**
	 * Minimum gap between two results in one scan.
	 *
	 * <p>A stronghold has twelve portal frames and an ancient city is paved with sculk catalysts.
	 * Without spacing, one scan would return one structure twelve times and fill the limit with it.</p>
	 */
	private static final int LANDMARK_SPACING = 8;

	/** Ticks since the last scan. Static because there is one bot and one memory. */
	private static int sinceScan;

	private Landmarks() {
	}

	/**
	 * Looks around, occasionally, and writes down anything worth remembering.
	 *
	 * <p>Called every tick and does nothing on almost all of them. Safe to call from anywhere on the
	 * client thread — it never moves the bot and never interrupts what it is doing.</p>
	 */
	public static void tick(Minecraft minecraft, LocalPlayer player) {
		int interval = BotSettings.LANDMARK_SCAN_INTERVAL.get();
		if (interval <= 0 || minecraft.level == null) {
			return;
		}
		if (++sinceScan < interval) {
			return;
		}
		sinceScan = 0;
		scan(minecraft, player);
	}

	private static void scan(Minecraft minecraft, LocalPlayer player) {
		ClientLevel level = minecraft.level;
		int radius = BotSettings.LANDMARK_SCAN_RADIUS.get();

		// Spaced out, so a stronghold's twelve portal frames do not fill the results with one place.
		List<BlockPos> found = BlockSearcher.findNearest(level, player.blockPosition(), radius,
				BotSettings.LANDMARK_SCAN_LIMIT.get(), LANDMARK_SPACING,
				state -> isLandmark(state.getBlock()));

		for (BlockPos pos : found) {
			String kind = meaning(minecraft, level.getBlockState(pos));
			if (kind != null) {
				// notice() does the deduplicating: it knows what is already written down and where.
				Places.notice(minecraft, kind, pos);
			}
		}
	}

	/**
	 * What this block says about where it is, or {@code null}.
	 *
	 * <p>The spawner is the one that depends on more than the block: in the nether it is a blaze
	 * spawner and so a fortress, and anywhere else it is a dungeon. Reading the dimension rather than
	 * the surrounding blocks keeps this a lookup, and it is right far more often than it is wrong.</p>
	 */
	private static String meaning(Minecraft minecraft, BlockState state) {
		if (state.is(Blocks.SPAWNER)) {
			return "the_nether".equals(Places.dimensionKey(minecraft)) ? "nether fortress" : "dungeon";
		}
		return MEANS.get(state.getBlock());
	}

	/**
	 * Whether this block is one the scan cares about.
	 *
	 * <p>{@link Blocks#SPAWNER} is deliberately not in {@link #MEANS} — what it means depends on the
	 * dimension — so it has to be named here as well, and forgetting that is exactly how a search that
	 * looked correct would have found no fortresses at all.</p>
	 */
	private static boolean isLandmark(Block block) {
		return block == Blocks.SPAWNER || MEANS.containsKey(block);
	}
}
