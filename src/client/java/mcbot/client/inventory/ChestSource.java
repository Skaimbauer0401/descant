package mcbot.client.inventory;

import mcbot.client.BotSettings;
import mcbot.client.path.BlockSearcher;
import mcbot.client.settings.SettingChoice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The ways of saying <em>which</em> container to bank the haul in.
 *
 * <p>Two, because they fail in opposite situations and so cover each other. Pointing at the chest is
 * exact, and is the only workable answer in a storage room where six chests sit in a row — but it
 * needs the chest to be in sight. Nearest needs nothing but proximity, and is the quicker of the two
 * when there is only one container around, which is the common case out in the world.</p>
 */
public enum ChestSource implements SettingChoice {

	/** Whatever the crosshair is on. */
	LOOKING_AT("looking", "the container the crosshair is pointing at"),

	/** The closest container to where the player is standing. */
	NEAREST("nearest", "the closest container to the player");

	private final String key;
	private final String description;

	ChestSource(String key, String description) {
		this.key = key;
		this.description = description;
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public String describe() {
		return description;
	}

	/**
	 * Finds the container this source refers to right now.
	 *
	 * @return where it is, or {@code null} if there is nothing to point at
	 */
	public BlockPos resolve(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.level == null) {
			return null;
		}
		return this == LOOKING_AT ? lookingAt(player) : nearest(minecraft, player);
	}

	/** Why {@link #resolve} came back empty, phrased as what to do about it. */
	public String hint() {
		return this == LOOKING_AT
				? "Point the crosshair at a chest, barrel or shulker box within "
						+ BotSettings.CHEST_LOOK_RANGE.asString() + " blocks — or use 'nearest' instead."
				: "No container within " + BotSettings.CHEST_SEARCH_RADIUS.asString()
						+ " blocks. Stand nearer to one, or look at it and use 'looking'.";
	}

	/**
	 * Raycasts from the eyes, ignoring the vanilla interaction limit.
	 *
	 * <p>{@code minecraft.hitResult} would have been free, but it only reaches as far as the player
	 * can touch — about four and a half blocks. Naming a chest is not touching it, and having to walk
	 * up to a chest in order to point at it would take away most of the reason to point rather than
	 * just stand next to it and ask for the nearest.</p>
	 */
	private static BlockPos lookingAt(LocalPlayer player) {
		HitResult hit = player.pick(BotSettings.CHEST_LOOK_RANGE.get(), 0.0f, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = blockHit.getBlockPos();
		return isContainer(player.level().getBlockState(pos)) ? pos : null;
	}

	private static BlockPos nearest(Minecraft minecraft, LocalPlayer player) {
		return BlockSearcher.findNearest(
				minecraft.level,
				BlockPos.containing(player.position()),
				BotSettings.CHEST_SEARCH_RADIUS.get(),
				ChestSource::isContainer);
	}

	/**
	 * Whether this block opens a container menu the deposit can shift-click into.
	 *
	 * <p>{@code AbstractChestBlock} covers the ordinary chest, the trapped chest and the ender chest
	 * in one test. The ender chest is worth having: on a long mining run it is the one container that
	 * is also back at base.</p>
	 */
	public static boolean isContainer(BlockState state) {
		return state.getBlock() instanceof AbstractChestBlock<?>
				|| state.getBlock() instanceof BarrelBlock
				|| state.getBlock() instanceof ShulkerBoxBlock;
	}
}
