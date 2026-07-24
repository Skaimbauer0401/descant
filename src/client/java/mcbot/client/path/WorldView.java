package mcbot.client.path;

import java.util.Set;

import mcbot.client.BotSettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Read-only view of the world, expressed in the vocabulary the pathfinder actually needs.
 *
 * <p>This is the single place that answers "can the player be here?", "can the player stand on
 * this?" and "is this going to hurt?". Keeping those judgements in one class means the pathfinder
 * and the movement executor can never disagree about what the terrain allows — a common source of
 * bots that plan a route they then refuse to walk.</p>
 *
 * <p>All queries are safe against unloaded chunks: {@link #isKnown(BlockPos)} must be consulted
 * before trusting any other answer, because {@code getBlockState} on an unloaded chunk returns air
 * and would otherwise look like a perfectly walkable void.</p>
 *
 * <p>A note on {@code blocksMotion()}: Mojang marks it deprecated as a "handle with care" flag
 * rather than a scheduled removal. It is used here regardless because it reads a value cached on
 * the block state, whereas the alternative — inspecting the collision shape — allocates. The
 * pathfinder calls this on the order of a hundred thousand times per search, so the cheap cached
 * answer is the right trade.</p>
 */
public final class WorldView {

	/**
	 * Cheap, plentiful blocks worth digging up purely to have something to build with.
	 *
	 * <p>An explicit whitelist rather than "anything solid": the bot needs scaffolding, not a
	 * licence to dismantle whatever happens to be nearest. Nobody wants it quarrying a wall of
	 * someone's house because it needed two blocks to bridge a gap.</p>
	 */
	private static final Set<Block> SCAFFOLD_SOURCES = Set.of(
			Blocks.DIRT,
			Blocks.COARSE_DIRT,
			Blocks.ROOTED_DIRT,
			Blocks.GRASS_BLOCK,
			Blocks.PODZOL,
			Blocks.STONE,
			Blocks.COBBLESTONE,
			Blocks.DEEPSLATE,
			Blocks.COBBLED_DEEPSLATE,
			Blocks.TUFF,
			Blocks.ANDESITE,
			Blocks.DIORITE,
			Blocks.GRANITE,
			Blocks.NETHERRACK,
			Blocks.END_STONE);

	/** Whether this block is worth mining just to obtain building material. */
	public static boolean isScaffoldSource(BlockState state) {
		return SCAFFOLD_SOURCES.contains(state.getBlock());
	}

	/** Blocks that damage or trap the player. The bot routes around these rather than through. */
	private static final Set<Block> HAZARDS = Set.of(
			Blocks.LAVA,
			Blocks.FIRE,
			Blocks.SOUL_FIRE,
			Blocks.CAMPFIRE,
			Blocks.SOUL_CAMPFIRE,
			Blocks.MAGMA_BLOCK,
			Blocks.CACTUS,
			Blocks.SWEET_BERRY_BUSH,
			Blocks.WITHER_ROSE,
			Blocks.POWDER_SNOW,
			Blocks.COBWEB);

	private final ClientLevel level;

	public WorldView(ClientLevel level) {
		this.level = level;
	}

	public ClientLevel level() {
		return level;
	}

	/** True when the chunk containing {@code pos} is loaded and its contents can be trusted. */
	public boolean isKnown(BlockPos pos) {
		return level.isLoaded(pos);
	}

	public BlockState state(BlockPos pos) {
		return level.getBlockState(pos);
	}

	// ---------------------------------------------------------------- terrain predicates

	/**
	 * True when the player's body may occupy this block — i.e. nothing solid and nothing harmful.
	 * Water counts as passable; the caller decides whether to pay the swimming cost.
	 */
	@SuppressWarnings("deprecation") // blocksMotion(): see note on the class-level javadoc
	public boolean isPassable(BlockPos pos) {
		BlockState state = state(pos);
		if (isHazard(pos, state)) {
			return false;
		}
		return !state.blocksMotion();
	}

	/**
	 * True when the player can stand on top of this block.
	 *
	 * <p>Requires a collision shape reaching (near) the top of the block. Partial blocks such as
	 * slabs are deliberately rejected: the whole path model assumes feet rest on a block boundary,
	 * and half-height supports would put the player half a block off the grid.</p>
	 */
	@SuppressWarnings("deprecation") // blocksMotion(): see note on the class-level javadoc
	public boolean isStandable(BlockPos pos) {
		BlockState state = state(pos);
		if (isHazard(pos, state) || !state.blocksMotion()) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= BotSettings.MIN_GROUND_HEIGHT;
	}

	/** True when a two-block-tall player fits with their feet at {@code feet}. */
	public boolean fitsAt(BlockPos feet) {
		return isPassable(feet) && isPassable(feet.above());
	}

	/** True when the player could stand here right now, without any building or mining. */
	public boolean canStandAt(BlockPos feet) {
		return fitsAt(feet) && isStandable(feet.below());
	}

	/**
	 * Height of the collision surface inside this block, measured from its own base.
	 *
	 * <p>Zero for empty space, 1.0 for a full block, and the awkward values in between for the
	 * partial blocks the grid model otherwise ignores — 0.5 for a slab, up to 0.875 for deep snow.
	 * Standing "at" a block position does not mean standing at its base when something like that
	 * is in the way.</p>
	 */
	public double surfaceHeight(BlockPos pos) {
		VoxelShape shape = state(pos).getCollisionShape(level, pos);
		return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
	}

	/**
	 * How far the player must climb to move from standing at {@code from} to standing at
	 * {@code to}, accounting for partial blocks underfoot at either end.
	 */
	public double climbHeight(BlockPos from, BlockPos to) {
		double fromSurface = from.getY() + surfaceHeight(from);
		double toSurface = to.getY() + surfaceHeight(to);
		return toSurface - fromSurface;
	}

	/**
	 * True when the player can climb this block — a ladder or a vine.
	 *
	 * <p>Baritone's rule exactly, and deliberately a short list rather than a tag. Minecraft's
	 * climbable tag also contains scaffolding and twisting vines, which climb with different physics
	 * (scaffolding is walked on top of, twisting vines shoot you upward) and would need their own
	 * handling to be worth planning through. Ladders and vines both behave the same way: hold forward
	 * against the wall to go up, let go to come down.</p>
	 */
	public boolean isClimbable(BlockPos pos) {
		Block block = state(pos).getBlock();
		return block == Blocks.LADDER || block == Blocks.VINE;
	}

	public boolean isWater(BlockPos pos) {
		return state(pos).getFluidState().is(FluidTags.WATER);
	}

	public boolean isLava(BlockPos pos) {
		return state(pos).getFluidState().is(FluidTags.LAVA);
	}

	public boolean isHazard(BlockPos pos) {
		return isHazard(pos, state(pos));
	}

	private boolean isHazard(BlockPos pos, BlockState state) {
		return HAZARDS.contains(state.getBlock()) || state.getFluidState().is(FluidTags.LAVA);
	}

	// ---------------------------------------------------------------- mining

	/**
	 * True when the bot is willing to mine this block.
	 *
	 * <p>Rejects unbreakable blocks, liquids (mining them does nothing) and blocks whose removal
	 * would be actively dangerous — anything holding back lava, or a falling block that would drop
	 * onto the bot's head.</p>
	 */
	public boolean isBreakable(BlockPos pos) {
		BlockState state = state(pos);
		if (state.isAir() || isFluid(state)) {
			return false;
		}
		if (state.getDestroySpeed(level, pos) < 0) {
			return false; // bedrock, barriers, portal frames
		}
		if (state.getBlock() instanceof FallingBlock || state(pos.above()).getBlock() instanceof FallingBlock) {
			return false; // sand/gravel would collapse into the space we just cleared
		}
		return !touchesLava(pos);
	}

	/** True when any face of this block is exposed to lava — mining it would flood the tunnel. */
	private boolean touchesLava(BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (isLava(pos.relative(dir))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Estimated ticks to mine {@code pos} with {@code tool}, or {@code -1} if it cannot be mined.
	 *
	 * <p>Mirrors vanilla's destroy-progress formula: each tick adds {@code speed / hardness / N} of
	 * progress, where N is 30 with the correct tool and 100 without.</p>
	 */
	public int estimateBreakTicks(BlockPos pos, ItemStack tool) {
		BlockState state = state(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0f) {
			return -1;
		}
		if (hardness == 0.0f) {
			return 1;
		}
		float speed = tool.getDestroySpeed(state);
		float progressPerTick = speed / hardness / (tool.isCorrectToolForDrops(state) ? 30.0f : 100.0f);
		if (progressPerTick <= 0.0f) {
			return -1;
		}
		return Math.max(1, (int) Math.ceil(1.0f / progressPerTick));
	}

	// ---------------------------------------------------------------- building

	/**
	 * True when a block could be placed at {@code pos} to bridge or pillar.
	 *
	 * <p>Placement needs a neighbouring solid face to click against, so an isolated position
	 * floating in mid-air is rejected even though it is empty.</p>
	 */
	public boolean isPlaceable(BlockPos pos) {
		return isFillable(pos) && findPlacementFace(pos) != null;
	}

	/**
	 * Whether this position is empty enough to take a block — ignoring whether anything is
	 * adjacent to click against.
	 *
	 * <p>Split out from {@link #isPlaceable} because the pathfinder has to answer the anchor
	 * question itself: a route that has already placed blocks can build off those, and the live
	 * world knows nothing about them.</p>
	 */
	public boolean isFillable(BlockPos pos) {
		return isFillable(level, pos);
	}

	/**
	 * The single definition of "empty enough to place a block here", shared by the planner
	 * ({@code PathFinder} via the instance method), the executor ({@code BlockPlacer}) and the
	 * controller. Keeping one copy is the point: this rule drifting between those three is exactly
	 * what let a snowed-over feet position read as filled to one and empty to another, so a stacked
	 * placement built the head block and skipped the foot.
	 *
	 * <p>Air and fluids need filling. So do replaceable partial blocks — a snow layer, tall grass —
	 * which read as occupied yet are neither the full cube the plan needs nor an obstacle to placing
	 * one: the right-click drops the real cube straight in, replacing them.</p>
	 */
	public static boolean isFillable(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return true;
		}
		return state.canBeReplaced() && !state.isCollisionShapeFullBlock(level, pos);
	}

	/**
	 * Finds a direction from {@code pos} towards a solid neighbour that can be clicked to place a
	 * block at {@code pos}, or {@code null} when the position is unsupported.
	 */
	public Direction findPlacementFace(BlockPos pos) {
		for (Direction dir : Direction.values()) {
			BlockPos neighbour = pos.relative(dir);
			// A full cube is required: a fence or slab face would send the placed block somewhere
			// other than where the path expects it.
			if (isKnown(neighbour) && state(neighbour).isCollisionShapeFullBlock(level, neighbour)) {
				return dir;
			}
		}
		return null;
	}

	/** True when this state is a fluid (water, lava, or a waterlogged block's fluid component). */
	private static boolean isFluid(BlockState state) {
		return !state.getFluidState().isEmpty();
	}
}
