package mcbot.client.action;

import mcbot.client.BotSettings;
import mcbot.client.control.Steering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs a furnace: loads it, waits for it, empties it.
 *
 * <p>The first station the bot can actually <em>operate</em> rather than merely open, and smelting is
 * the one that most limits what it can be asked for — ore is worthless until it has been through
 * one.</p>
 *
 * <p>Items go in <b>one at a time by cursor</b> rather than by shift-clicking the stack across. A
 * shift-click moves everything, so asking for six iron out of a stack of thirty would smelt all
 * thirty — the same overshoot the hunt quota had, arrived at from a different direction. Placing
 * exactly what was asked for costs a click per item and is worth it.</p>
 */
public final class Smelter {

	/** Vanilla cook time for one item, in ticks. Used to work out how much fuel to put in. */
	private static final int TICKS_PER_ITEM = 200;

	/** How long to wait for the menu, or for one loading click to register. */
	private static final int STEP_TIMEOUT_TICKS = 60;

	/** Ticks between clicks, so the server is never behind our idea of the menu. */
	private static final int INTERVAL_TICKS = 2;

	private enum Phase {
		/** Facing the furnace and clicking it. */
		OPENING,
		/** Putting fuel in. */
		LOADING_FUEL,
		/** Putting the raw material in. */
		LOADING_INPUT,
		/** Standing by while it burns. */
		SMELTING,
		/** Taking the finished items out. */
		TAKING
	}

	private BlockPos furnace;
	private Item input;
	private Item fuel;
	private int inputWanted;
	private int fuelWanted;
	private Phase phase;
	private int ticks;
	private int idleTicks;

	/** Where the cursor's stack came from, so the remainder can be put back. */
	private int cursorSource = -1;
	private int stillToPlace;

	public void begin(BlockPos furnace, Item input, Item fuel, int count, int fuelPieces) {
		this.furnace = furnace.immutable();
		this.input = input;
		this.fuel = fuel;
		this.inputWanted = count;
		this.fuelWanted = fuelPieces;
		this.phase = Phase.OPENING;
		this.ticks = 0;
		this.idleTicks = 0;
		this.cursorSource = -1;
		this.stillToPlace = 0;
	}

	public void cancel(Minecraft minecraft) {
		closeMenu(minecraft);
		furnace = null;
		phase = null;
	}

	/** How long to allow before giving up, given how much was asked for. */
	public int patienceTicks() {
		return inputWanted * TICKS_PER_ITEM + 400;
	}

	/** Fuel pieces needed to smelt {@code count} items with this fuel. */
	public static int fuelNeeded(Minecraft minecraft, ItemStack fuel, int count) {
		int burn = minecraft.level == null ? 0 : minecraft.level.fuelValues().burnDuration(fuel);
		if (burn <= 0) {
			return 0; // not a fuel at all
		}
		return Math.max(1, (int) Math.ceil((double) count * TICKS_PER_ITEM / burn));
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player) {
		if (furnace == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}
		ticks++;

		Vec3 centre = Vec3.atCenterOf(furnace);
		if (player.getEyePosition().distanceTo(centre) > BotSettings.REACH.get()) {
			cancel(minecraft);
			return ActionState.OUT_OF_RANGE;
		}

		return switch (phase) {
			case OPENING -> tickOpening(minecraft, player, centre);
			case LOADING_FUEL -> load(minecraft, player, fuel, fuelWanted,
					AbstractFurnaceMenu.FUEL_SLOT, Phase.LOADING_INPUT);
			case LOADING_INPUT -> load(minecraft, player, input, inputWanted,
					AbstractFurnaceMenu.INGREDIENT_SLOT, Phase.SMELTING);
			case SMELTING -> tickSmelting(minecraft, player);
			case TAKING -> tickTaking(minecraft, player);
		};
	}

	private ActionState tickOpening(Minecraft minecraft, LocalPlayer player, Vec3 centre) {
		if (player.containerMenu instanceof AbstractFurnaceMenu) {
			phase = Phase.LOADING_FUEL;
			ticks = 0;
			return ActionState.WORKING;
		}
		if (ticks > STEP_TIMEOUT_TICKS) {
			cancel(minecraft);
			return ActionState.FAILED;
		}

		Vec3 eye = player.getEyePosition();
		player.setYRot(Steering.approach(player.getYRot(), Steering.yawTowards(player.position(), centre)));
		player.setXRot(Steering.approach(player.getXRot(), Steering.pitchTowards(eye, centre)));

		if (ticks % INTERVAL_TICKS == 0) {
			Direction face = Direction.getApproximateNearest(
					eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
			minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(centre, face, furnace, false));
			player.swing(InteractionHand.MAIN_HAND);
		}
		return ActionState.WORKING;
	}

	/**
	 * Moves exactly {@code wanted} of an item into one furnace slot, a click at a time.
	 *
	 * <p>Pick the whole stack up, right-click the target once per item, then put the remainder back
	 * where it came from. Fiddly, but it is the only way to place a precise number: every bulk move
	 * the container API offers takes the lot.</p>
	 */
	private ActionState load(Minecraft minecraft, LocalPlayer player, Item item, int wanted,
			int targetSlot, Phase next) {
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			cancel(minecraft);
			return ActionState.FAILED; // the furnace closed under us
		}
		if (ticks % INTERVAL_TICKS != 0) {
			return ActionState.WORKING; // pace the clicks
		}

		if (stillToPlace == 0 && cursorSource < 0) {
			stillToPlace = wanted - menu.slots.get(targetSlot).getItem().getCount();
			if (stillToPlace <= 0) {
				phase = next;
				ticks = 0;
				return ActionState.WORKING; // already enough in there
			}
		}

		// Nothing on the cursor: pick a stack up.
		if (cursorSource < 0) {
			int source = findStack(player, item);
			if (source < 0) {
				cancel(minecraft);
				return ActionState.NO_MATERIAL;
			}
			cursorSource = source;
			click(minecraft, player, source, 0, ContainerInput.PICKUP);
			return ActionState.WORKING;
		}

		// Holding a stack: drop one in, or put the rest back and move on.
		if (stillToPlace > 0 && !player.containerMenu.getCarried().isEmpty()) {
			click(minecraft, player, targetSlot, 1, ContainerInput.PICKUP); // right-click = one item
			stillToPlace--;
			return ActionState.WORKING;
		}

		click(minecraft, player, cursorSource, 0, ContainerInput.PICKUP); // remainder back
		cursorSource = -1;
		if (stillToPlace > 0) {
			return ActionState.WORKING; // that stack ran out; the next tick picks up another
		}
		phase = next;
		ticks = 0;
		return ActionState.WORKING;
	}

	private ActionState tickSmelting(Minecraft minecraft, LocalPlayer player) {
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			cancel(minecraft);
			return ActionState.FAILED;
		}
		boolean inputGone = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty();
		boolean anythingOut = !menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty();

		if (inputGone && anythingOut) {
			phase = Phase.TAKING;
			return ActionState.WORKING;
		}
		// Nothing burning and nothing produced: out of fuel, or it was never smeltable. Waiting out
		// the full patience for that would stand the bot in front of a cold furnace for a minute.
		if (menu.getLitProgress() <= 0.0f && !anythingOut && ++idleTicks > STEP_TIMEOUT_TICKS) {
			cancel(minecraft);
			return ActionState.NO_MATERIAL;
		}
		if (menu.getLitProgress() > 0.0f) {
			idleTicks = 0;
		}
		return ticks > patienceTicks() ? takeWhatThereIs(minecraft, player) : ActionState.WORKING;
	}

	private ActionState tickTaking(Minecraft minecraft, LocalPlayer player) {
		return takeWhatThereIs(minecraft, player);
	}

	/** Empties the result slot and finishes, whether or not everything smelted. */
	private ActionState takeWhatThereIs(Minecraft minecraft, LocalPlayer player) {
		if (player.containerMenu instanceof AbstractFurnaceMenu menu
				&& !menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
			click(minecraft, player, AbstractFurnaceMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);
			return ActionState.WORKING; // come back next tick to confirm it emptied
		}
		closeMenu(minecraft);
		furnace = null;
		phase = null;
		return ActionState.DONE;
	}

	// ---------------------------------------------------------------- plumbing

	private static void click(Minecraft minecraft, LocalPlayer player, int slot, int button,
			ContainerInput kind) {
		minecraft.gameMode.handleContainerInput(player.containerMenu.containerId, slot, button, kind,
				player);
	}

	/** A menu slot backed by the player's own inventory holding this item, or {@code -1}. */
	private static int findStack(LocalPlayer player, Item item) {
		for (Slot slot : player.containerMenu.slots) {
			if (slot.container instanceof Inventory && slot.getItem().is(item)) {
				return slot.index;
			}
		}
		return -1;
	}

	private static void closeMenu(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}
}
