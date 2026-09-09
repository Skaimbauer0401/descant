package descant.client.action;

import descant.client.BotSettings;
import descant.client.control.Steering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Makes things, in the inventory's 2x2 grid or at a crafting table's 3x3.
 *
 * <p>Both go through the recipe book: one packet asks the server to lay the whole recipe out in the
 * grid, and the result is then shift-clicked into the inventory. That saves knowing any recipe's
 * shape, and means the server does the validating — the same route a person clicking the recipe book
 * takes.</p>
 *
 * <p>A state machine because none of it is instant. Opening a table waits on the server to send the
 * menu; laying a recipe out waits on the server to send the filled slots back. Assuming either had
 * happened would shift-click an empty result slot and quietly craft nothing.</p>
 */
public final class Crafter {

	/** How long to wait for the table's menu, or for a placed recipe to appear. */
	private static final int STEP_TIMEOUT_TICKS = 40;

	/** Ticks between clicks, so the server is never behind our idea of the menu. */
	private static final int INTERVAL_TICKS = 2;

	private enum Phase {
		/** Facing the crafting table and clicking it. */
		OPENING,
		/** Asking the server to lay the recipe out in the grid. */
		PLACING,
		/** Taking what came out. */
		TAKING
	}

	/** The table being used, or {@code null} when crafting in the inventory's own 2x2. */
	private BlockPos table;
	private RecipeDisplayId recipe;
	private int remaining;
	private int made;
	private Phase phase;
	private int ticks;

	/**
	 * @param table  the crafting table to use, or {@code null} to use the inventory's 2x2 grid
	 * @param crafts how many times to run the recipe
	 */
	public void begin(BlockPos table, RecipeDisplayId recipe, int crafts) {
		this.table = table == null ? null : table.immutable();
		this.recipe = recipe;
		this.remaining = crafts;
		this.made = 0;
		this.phase = table == null ? Phase.PLACING : Phase.OPENING;
		this.ticks = 0;
	}

	public void cancel(Minecraft minecraft) {
		closeMenu(minecraft);
		recipe = null;
		phase = null;
	}

	/** How many times the recipe has been run so far. */
	public int made() {
		return made;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player) {
		if (recipe == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}
		if (++ticks > STEP_TIMEOUT_TICKS) {
			// Whatever we were waiting for is not coming. Bail rather than sit here: a wrong guess
			// about the menu state is the one way this can spin without ever finishing.
			cancel(minecraft);
			return made > 0 ? ActionState.DONE : ActionState.FAILED;
		}

		return switch (phase) {
			case OPENING -> tickOpening(minecraft, player);
			case PLACING -> tickPlacing(minecraft, player);
			case TAKING -> tickTaking(minecraft, player);
		};
	}

	private ActionState tickOpening(Minecraft minecraft, LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			phase = Phase.PLACING;
			ticks = 0;
			return ActionState.WORKING;
		}

		Vec3 centre = Vec3.atCenterOf(table);
		// Slack on purpose: REACH is what the bot needs to *break* a block, but half the distance to a
		// container's centre is inside the container. Failing on that put the bot into a loop of
		// walking away and coming back without ever opening anything.
		if (player.getEyePosition().distanceTo(centre) > BotSettings.REACH.get() + 1.5) {
			cancel(minecraft);
			return ActionState.OUT_OF_RANGE;
		}

		Vec3 eye = player.getEyePosition();
		player.setYRot(Steering.approach(player.getYRot(), Steering.yawTowards(player.position(), centre)));
		player.setXRot(Steering.approach(player.getXRot(), Steering.pitchTowards(eye, centre)));

		// Only every few ticks: spamming right-click opens and closes the table repeatedly and it
		// never settles into a usable menu.
		if (ticks % INTERVAL_TICKS == 0) {
			Direction face = Direction.getApproximateNearest(
					eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
			minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(centre, face, table, false));
			player.swing(InteractionHand.MAIN_HAND);
		}
		return ActionState.WORKING;
	}

	private ActionState tickPlacing(Minecraft minecraft, LocalPlayer player) {
		if (table != null && player.containerMenu == player.inventoryMenu) {
			// The table closed under us.
			cancel(minecraft);
			return made > 0 ? ActionState.DONE : ActionState.FAILED;
		}
		if (remaining <= 0) {
			closeMenu(minecraft);
			recipe = null;
			phase = null;
			return ActionState.DONE;
		}

		// false = one craft's worth, not "as many as the ingredients allow". Doing them one at a time
		// is what lets a count mean what it says.
		minecraft.gameMode.handlePlaceRecipe(player.containerMenu.containerId, recipe, false);
		phase = Phase.TAKING;
		ticks = 0;
		return ActionState.WORKING;
	}

	private ActionState tickTaking(Minecraft minecraft, LocalPlayer player) {
		Slot result = resultSlot(player);
		if (result == null) {
			cancel(minecraft);
			return made > 0 ? ActionState.DONE : ActionState.FAILED;
		}
		if (result.getItem().isEmpty()) {
			// The server has not filled the grid yet, or could not: the timeout above decides which.
			return ActionState.WORKING;
		}

		// Shift-click: moves the finished item into the inventory and, in a crafting menu, refills the
		// grid for another go if the ingredients are there.
		minecraft.gameMode.handleContainerInput(player.containerMenu.containerId, result.index, 0,
				ContainerInput.QUICK_MOVE, player);
		made++;
		remaining--;
		phase = Phase.PLACING;
		ticks = 0;
		return ActionState.WORKING;
	}

	/**
	 * The slot a finished item appears in.
	 *
	 * <p>Found by its type rather than by index. The inventory's 2x2 and a table's 3x3 are different
	 * menus with different layouts, and hard-coding either index would quietly click the wrong slot in
	 * the other — the same reasoning as identifying chest slots by their container.</p>
	 */
	private static Slot resultSlot(LocalPlayer player) {
		for (Slot slot : player.containerMenu.slots) {
			if (slot instanceof ResultSlot) {
				return slot;
			}
		}
		return null;
	}

	private static void closeMenu(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}
}
