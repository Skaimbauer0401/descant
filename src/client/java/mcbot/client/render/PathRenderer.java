package mcbot.client.render;

import mcbot.client.BotSettings;
import mcbot.client.control.BotController;
import mcbot.client.path.Path;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.entity.Entity;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the planned route in the world as a line, with markers for blocks to be mined or placed.
 *
 * <p>Uses Minecraft 26.2's built-in {@code Gizmos} debug-drawing API rather than hand-rolled vertex
 * buffers. {@code Gizmos} resolves its target through a {@link ThreadLocal} collector and throws if
 * none is registered, so every call must sit inside
 * {@code LevelRenderer.collectPerFrameRenderThreadGizmos()} — an {@link AutoCloseable} that installs
 * the collector and restores the previous one on close.</p>
 *
 * <p>Everything is drawn always-on-top. For a pathfinding bot, seeing the route continue through a
 * hill is the entire point — a depth-tested line would vanish exactly where it is most interesting.
 * </p>
 */
public final class PathRenderer {

	// A restrained palette: one cyan accent for the route, warm tones reserved for the two things
	// that modify the world, and green only for the destination.
	private static final int COLOUR_ROUTE = 0xFF00E5FF;
	private static final int COLOUR_WALKED = 0x5500E5FF;
	private static final int COLOUR_BREAK = 0xFFFF5252;
	private static final int COLOUR_PLACE = 0xFFFFC400;
	private static final int COLOUR_GOAL = 0xFF00E676;
	private static final int COLOUR_QUARRY = 0xFFFF4081;

	// Translucent fills, so an actively-worked block reads as filled rather than merely outlined.
	private static final int FILL_BREAK = 0x40FF5252;
	private static final int FILL_PLACE = 0x40FFC400;

	private static final float ROUTE_WIDTH = 4.0f;
	private static final float WALKED_WIDTH = 2.0f;

	/** Lifts the line off the floor so it is not z-fighting with the blocks it runs across. */
	private static final double LINE_HEIGHT = 0.12;

	/**
	 * Bound on drawn segments. A single plan searches up to {@code MAX_NODES} (20k) and can return a
	 * route several hundred nodes long, so this is set well above that to show the route to its end
	 * rather than clipping it partway. It stays a bound, not a removal: a pathological plan should
	 * never spend a frame emitting thousands of always-on-top lines.
	 */
	private static final int MAX_SEGMENTS = 1024;

	private final BotController controller;

	public PathRenderer(BotController controller) {
		this.controller = controller;
	}

	public void register() {
		LevelRenderEvents.BEFORE_GIZMOS.register(this::render);
	}

	private void render(LevelRenderContext context) {
		if (!BotSettings.SHOW_PATH.get()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || !controller.isActive()) {
			return;
		}

		// Note the path may be null while mining, fighting or collecting — those are exactly the
		// moments the activity highlights matter most, so drawing must not depend on having a route.
		Path path = controller.currentPath();

		// Installs the gizmo collector for the duration of the block; without it every Gizmos call
		// throws IllegalStateException.
		try (var collection = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
			if (path != null && !path.isEmpty()) {
				drawRoute(path, controller.currentStep(), minecraft.player.position());
				drawWorkMarkers(path, controller.currentStep());
			}
			drawGoal(controller.goal());
			drawCurrentActivity();
		}
	}

	/**
	 * Highlights what the bot is doing <em>right now</em>, as opposed to what it plans to do.
	 *
	 * <p>The route markers show intent; these show action. Without them a bot standing still is
	 * indistinguishable from a bot stuck, and most of the debugging in this project came down to
	 * telling those two apart.</p>
	 */
	private void drawCurrentActivity() {
		BlockPos breaking = controller.activeBreakTarget();
		if (breaking != null) {
			Gizmos.cuboid(breaking, GizmoStyle.strokeAndFill(COLOUR_BREAK, 4.0f, FILL_BREAK))
					.setAlwaysOnTop();
			Gizmos.billboardTextOverBlock("mining", breaking, COLOUR_BREAK, 0, 1.0f)
					.setAlwaysOnTop();
		}

		BlockPos placing = controller.activePlaceTarget();
		if (placing != null) {
			Gizmos.cuboid(placing, GizmoStyle.strokeAndFill(COLOUR_PLACE, 4.0f, FILL_PLACE))
					.setAlwaysOnTop();
			Gizmos.billboardTextOverBlock("placing", placing, COLOUR_PLACE, 0, 1.0f)
					.setAlwaysOnTop();
		}

		Entity quarry = controller.huntedEntity();
		if (quarry != null && quarry.isAlive()) {
			Gizmos.cuboid(quarry.getBoundingBox().inflate(0.05), GizmoStyle.stroke(COLOUR_QUARRY))
					.setAlwaysOnTop();
			Gizmos.billboardTextOverBlock("target", BlockPos.containing(
					quarry.position().add(0.0, quarry.getBbHeight(), 0.0)),
					COLOUR_QUARRY, 0, 1.0f).setAlwaysOnTop();
		}
	}

	/** The polyline itself: faint behind us, bright ahead, anchored to the player's feet. */
	private void drawRoute(Path path, int stepIndex, Vec3 playerPosition) {
		int lastIndex = path.size() - 1;
		int next = Math.min(stepIndex, lastIndex);

		// The part already walked, kept faint so the route reads directionally at a glance.
		Vec3 cursor = pointAt(path.step(0).pos());
		for (int index = 1; index < next; index++) {
			Vec3 point = pointAt(path.step(index).pos());
			Gizmos.line(cursor, point, COLOUR_WALKED, WALKED_WIDTH).setAlwaysOnTop();
			cursor = point;
		}

		// Join the live player position to the route so the line never appears detached.
		Vec3 from = playerPosition.add(0.0, LINE_HEIGHT, 0.0);
		int end = Math.min(lastIndex, next + MAX_SEGMENTS);
		for (int index = next; index <= end; index++) {
			Vec3 point = pointAt(path.step(index).pos());
			Gizmos.line(from, point, COLOUR_ROUTE, ROUTE_WIDTH).setAlwaysOnTop();
			from = point;
		}
	}

	/** Boxes around every block the plan intends to mine or fill in. */
	private void drawWorkMarkers(Path path, int stepIndex) {
		int lastIndex = path.size() - 1;
		int start = Math.min(stepIndex, lastIndex);
		int end = Math.min(lastIndex, start + MAX_SEGMENTS);

		for (int index = start; index <= end; index++) {
			Path.Step step = path.step(index);
			for (BlockPos breaking : step.toBreak()) {
				Gizmos.cuboid(breaking, GizmoStyle.stroke(COLOUR_BREAK)).setAlwaysOnTop();
			}
			if (step.toPlace() != null) {
				Gizmos.cuboid(step.toPlace(), GizmoStyle.stroke(COLOUR_PLACE)).setAlwaysOnTop();
			}
		}
	}

	private void drawGoal(BlockPos goal) {
		if (goal == null) {
			return;
		}
		Gizmos.cuboid(goal, GizmoStyle.stroke(COLOUR_GOAL)).setAlwaysOnTop();
		// The goal marker doubles as the status readout, so the current activity is legible from
		// wherever the bot happens to be looking.
		Gizmos.billboardTextOverBlock(controller.activityLabel(), goal, COLOUR_GOAL, 0, 1.0f)
				.setAlwaysOnTop();
	}

	private static Vec3 pointAt(BlockPos pos) {
		return Vec3.atBottomCenterOf(pos).add(0.0, LINE_HEIGHT, 0.0);
	}
}
