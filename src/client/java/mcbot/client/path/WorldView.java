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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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

	/**
	 * Workstations and furniture the bot refuses to mine its way through.
	 *
	 * <p>Only the ones that have no block entity to give them away. Everything else that someone put
	 * somewhere on purpose — chests, furnaces, beds, signs, hives, spawners — carries one, and
	 * {@link EntityBlock} catches the lot without a list to keep up to date.</p>
	 *
	 * <p>The point is that a route is not worth a base. The pathfinder digs through whatever stands
	 * between it and the goal, and "go back to base" ends at exactly the place where the things in the
	 * way are the crafting table and the chests — so the bot arrives having demolished what it was
	 * sent to. Routing around costs a few seconds; the alternative costs whatever was in the chest.</p>
	 */
	private static final Set<Block> PROTECTED = Set.of(
			Blocks.CRAFTING_TABLE,
			Blocks.SMITHING_TABLE,
			Blocks.CARTOGRAPHY_TABLE,
			Blocks.FLETCHING_TABLE,
			Blocks.LOOM,
			Blocks.STONECUTTER,
			Blocks.GRINDSTONE,
			Blocks.ANVIL,
			Blocks.CHIPPED_ANVIL,
			Blocks.DAMAGED_ANVIL,
			Blocks.COMPOSTER,
			Blocks.LODESTONE,
			Blocks.BOOKSHELF);

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
		if (state.isAir()) {
			return true; // far and away the common case; take it before touching anything else
		}
		if (isHazard(pos, state)) {
			return false;
		}
		if (isOpenable(state)) {
			return true; // shut right now, but the bot can open it — see isOpenable
		}
		if (state.blocksMotion()) {
			return false;
		}
		// Not flagged as motion-blocking, yet plenty of small attached blocks still have a collision
		// box the player walks straight into. A cocoa pod on a jungle log is the one that catches this
		// bot: the planner routed through the pod's cell and then wedged against it, with nothing in
		// the model to explain why it had stopped. The collision shape is the authority, so ask it —
		// only for non-air blocks that claimed not to block motion, which keeps the cost off the hot
		// path. Blocks with genuinely no collision (grass, flowers) answer from a shared empty shape.
		return state.getCollisionShape(level, pos).isEmpty();
	}

	/**
	 * A door or fence gate the bot can open by clicking it.
	 *
	 * <p>These count as <em>passable</em> to the planner whether they are open or shut, which is
	 * Baritone's rule and at first glance a lie about the world. It is the right lie: a shut door is
	 * not an obstacle, it is a one-click delay, and treating it as a wall makes the bot tunnel through
	 * someone's house rather than use the doorway. Baritone charges nothing extra for it either.</p>
	 *
	 * <p>Iron doors and iron trapdoors are excluded — those need a redstone signal, so for a bot with
	 * no lever-pulling behaviour they really are walls.</p>
	 */
	public static boolean isOpenable(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR) {
			return false;
		}
		return block instanceof DoorBlock || block instanceof FenceGateBlock;
	}

	/** Whether this block is an openable door/gate that is currently shut, so it needs a click. */
	public boolean isShutDoor(BlockPos pos) {
		BlockState state = state(pos);
		return isOpenable(state) && !state.getValue(BlockStateProperties.OPEN);
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
		if (isOpenable(state)) {
			// A shut door is a full-height, paper-thin slab of collision. Its shape reaches the top of
			// the block, so without this it reads as perfectly good ground to stand on.
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= BotSettings.MIN_GROUND_HEIGHT;
	}

	/** True when a two-block-tall player fits with their feet at {@code feet}. */
	public boolean fitsAt(BlockPos feet) {
		return isPassable(feet) && isPassable(feet.above());
	}

	/**
	 * True when a block half-fills its own cell and the player stands on top of it <em>inside</em>
	 * that cell — a bottom slab being the everyday case.
	 *
	 * <p>This is the one shape the block-grid model genuinely cannot express, and why slabs were
	 * excluded outright for so long. Everywhere else, "standing at cell P" means the feet sit on P's
	 * floor and something solid fills P−1. On a bottom slab the feet sit at {@code P.y + 0.5} — still
	 * within P, because that is where the coordinate floors to — so the support and the stance occupy
	 * the <em>same</em> cell. Treating that as a separate kind of footing is what lets the planner use
	 * slabs without the node coordinate and the player's real position drifting apart.</p>
	 *
	 * <p>Deliberately narrow: stairs and top slabs have collision reaching the top of their cell, so
	 * they are ordinary {@link #isStandable} ground and are not covered here.</p>
	 */
	@SuppressWarnings("deprecation") // blocksMotion(): see note on the class-level javadoc
	public boolean isHalfSupport(BlockPos pos) {
		BlockState state = state(pos);
		if (state.isAir() || isHazard(pos, state) || !state.blocksMotion()) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= BotSettings.MIN_HALF_GROUND_HEIGHT && top < BotSettings.MIN_GROUND_HEIGHT;
	}

	/**
	 * Standing surface height for a player whose feet are at {@code feet}, measured from that cell's
	 * base — {@code 0.5} on a bottom slab, {@code 0} on ordinary ground.
	 */
	public double stanceHeight(BlockPos feet) {
		return isHalfSupport(feet) ? surfaceHeight(feet) : 0.0;
	}

	/**
	 * True when the player could stand here right now, without any building or mining.
	 *
	 * <p>The half-support case needs an extra cell of headroom. Standing on a bottom slab raises the
	 * whole body by half a block, so the head reaches into the cell two above rather than stopping at
	 * the top of the one above.</p>
	 */
	public boolean canStandAt(BlockPos feet) {
		if (isHalfSupport(feet)) {
			return isPassable(feet.above()) && isPassable(feet.above(2));
		}
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
	 * onto the bot's head. Also anything that was clearly put there on purpose: see
	 * {@link #isProtected}.</p>
	 *
	 * <p>Only about clearing the way. An explicit {@code mine} or a hunt aimed at one of these still
	 * breaks it, and should — a refusal to do the thing it was asked for outright would be a different
	 * and worse behaviour than declining to do it by accident.</p>
	 */
	public boolean isBreakable(BlockPos pos) {
		BlockState state = state(pos);
		if (state.isAir() || isFluid(state)) {
			return false;
		}
		if (state.getDestroySpeed(level, pos) < 0) {
			return false; // bedrock, barriers, portal frames
		}
		if (isProtected(state)) {
			return false;
		}
		if (state.getBlock() instanceof FallingBlock || state(pos.above()).getBlock() instanceof FallingBlock) {
			return false; // sand/gravel would collapse into the space we just cleared
		}
		return !touchesLava(pos);
	}

	/**
	 * Whether this is something somebody built, rather than terrain.
	 *
	 * <p>A block entity is the giveaway: chests, furnaces, beds, signs, banners, hives, spawners and
	 * every other block that has to remember something all carry one, and nothing that occurs as plain
	 * ground does. That covers the whole category without a list, and keeps covering it as versions add
	 * to it. {@link #PROTECTED} then names the handful of workstations — the crafting table above all —
	 * that store nothing and so have no block entity to be recognised by.</p>
	 */
	public static boolean isProtected(BlockState state) {
		if (!BotSettings.PROTECT_BUILT.get()) {
			return false;
		}
		return state.getBlock() instanceof EntityBlock || PROTECTED.contains(state.getBlock());
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
