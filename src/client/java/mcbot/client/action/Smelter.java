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
 * <p>Everything here is done with <b>shift-clicks</b> ({@code QUICK_MOVE}) and nothing else. The
 * first version placed items one at a time with the cursor, to load an exact number — pick the stack
 * up, right-click the target once per item, put the remainder back. It was precise on paper and did
 * not work in the game, and a three-step cursor dance across a menu the server is also editing has
 * far too many places to go wrong for the precision it bought.</p>
 *
 * <p>So the whole stack goes in, and the count is honoured at the <em>other</em> end: the moment the
 * result slot holds what was asked for, the leftovers are pulled straight back out. Anything the
 * furnace finished in the meantime is still an ingot in your pocket, which is a far better way to be
 * wrong than a furnace that never lights.</p>
 */
public final class Smelter {

	/** Vanilla cook time for one item, in ticks. Used to work out how much fuel to put in. */
	private static final int TICKS_PER_ITEM = 200;

	/** How long to wait for the menu to open. */
	private static final int OPEN_TIMEOUT_TICKS = 60;

	/**
	 * How long a cold, idle furnace is tolerated before giving up.
	 *
	 * <p>Generous, because a furnace that has just been loaded takes a moment to light and the client
	 * hears about it a little later still. Too short a fuse here reads as "no fuel" when the truth was
	 * "not yet".
	 */
	private static final int STALL_TIMEOUT_TICKS = 120;

	/** Ticks between clicks, so the server is never behind our idea of the menu. */
	private static final int INTERVAL_TICKS = 3;

	private enum Phase {
		/** Facing the furnace and clicking it. */
		OPENING,
		/** Shift-clicking fuel and raw material in. */
		LOADING,
		/** Standing by while it burns. */
		SMELTING,
		/** Pulling out the result and whatever was not used. */
		EMPTYING
	}

	private BlockPos furnace;
	private Item input;
	private Item fuel;
	private int wanted;
	private Phase phase;
	private int ticks;

	/** Watches for a furnace that is doing nothing, so a stall is told apart from slow progress. */
	private int lastResultCount;
	private int stalledTicks;

	/** What the result slot held when we started, so only what we smelted is counted. */
	private int resultAtStart = -1;

	/**
	 * Which step went wrong, in words.
	 *
	 * <p>Worth carrying rather than folding every failure into one message. "Couldn't open it",
	 * "couldn't find the coal in the menu" and "it never lit" are three different bugs wearing the
	 * same coat, and being told which one happened is the difference between fixing it and guessing
	 * again.</p>
	 */
	private String problem = "";

	public void begin(BlockPos furnace, Item input, Item fuel, int count) {
		this.furnace = furnace.immutable();
		this.input = input;
		this.fuel = fuel;
		this.wanted = count;
		this.phase = Phase.OPENING;
		this.ticks = 0;
		this.lastResultCount = 0;
		this.stalledTicks = 0;
		this.resultAtStart = -1;
		this.problem = "";
	}

	public void cancel(Minecraft minecraft) {
		closeMenu(minecraft);
		furnace = null;
		phase = null;
	}

	public boolean isRunning() {
		return furnace != null;
	}

	/** Which step failed, for the message the player and the model both read. */
	public String problem() {
		return problem;
	}

	/** Fuel pieces needed to smelt {@code count} items with this fuel. */
	public static int fuelNeeded(Minecraft minecraft, ItemStack fuel, int count) {
		int burn = minecraft.level == null ? 0 : minecraft.level.fuelValues().burnDuration(fuel);
		return burn <= 0 ? 0 : Math.max(1, (int) Math.ceil((double) count * TICKS_PER_ITEM / burn));
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player) {
		if (furnace == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}
		ticks++;

		// Measured to the block's near face rather than its centre, and with a little slack. Half a
		// block of the distance to a container is inside the container, and failing on that put the
		// bot into a walk-away-and-come-back loop that never loaded anything.
		Vec3 centre = Vec3.atCenterOf(furnace);
		if (player.getEyePosition().distanceTo(centre) > BotSettings.REACH.get() + 1.5) {
			cancel(minecraft);
			return ActionState.OUT_OF_RANGE;
		}

		return switch (phase) {
			case OPENING -> tickOpening(minecraft, player, centre);
			case LOADING -> tickLoading(minecraft, player);
			case SMELTING -> tickSmelting(minecraft, player);
			case EMPTYING -> tickEmptying(minecraft, player);
		};
	}

	private ActionState tickOpening(Minecraft minecraft, LocalPlayer player, Vec3 centre) {
		if (player.containerMenu instanceof AbstractFurnaceMenu) {
			phase = Phase.LOADING;
			ticks = 0;
			return ActionState.WORKING;
		}
		if (ticks > OPEN_TIMEOUT_TICKS) {
			problem = "the furnace never opened";
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
	 * Shift-clicks fuel and then raw material into the furnace.
	 *
	 * <p>A furnace menu already knows where each belongs — fuel to the fuel slot, anything smeltable
	 * to the ingredient slot — so the destination never has to be named. That is precisely why this
	 * works where hand-placing did not.</p>
	 */
	private ActionState tickLoading(Minecraft minecraft, LocalPlayer player) {
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			problem = "the furnace closed while loading it";
			cancel(minecraft);
			return ActionState.FAILED;
		}
		if (ticks % INTERVAL_TICKS != 0) {
			return ActionState.WORKING; // pace the clicks
		}

		if (menu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem().isEmpty()) {
			int slot = findStack(player, fuel);
			if (slot < 0) {
				problem = "no fuel left to put in";
				cancel(minecraft);
				return ActionState.NO_MATERIAL;
			}
			quickMove(minecraft, player, slot);
			return ActionState.WORKING;
		}

		Slot ingredient = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT);
		if (ingredient.getItem().getCount() < wanted) {
			int slot = findStack(player, input);
			if (slot >= 0) {
				quickMove(minecraft, player, slot);
				return ActionState.WORKING;
			}
			// Nothing left to add. Whatever went in is what gets smelted, which is still a result.
			if (ingredient.getItem().isEmpty()) {
				problem = "nothing went into the furnace";
				cancel(minecraft);
				return ActionState.NO_MATERIAL;
			}
		}

		resultAtStart = menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
		lastResultCount = resultAtStart;
		phase = Phase.SMELTING;
		ticks = 0;
		return ActionState.WORKING;
	}

	private ActionState tickSmelting(Minecraft minecraft, LocalPlayer player) {
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			cancel(minecraft);
			return ActionState.FAILED;
		}

		int produced = menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount() - resultAtStart;
		boolean inputGone = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty();

		if (produced >= wanted || inputGone) {
			phase = Phase.EMPTYING;
			ticks = 0;
			return ActionState.WORKING;
		}

		// Progress is either the result growing or the fire being lit. Neither for a while means it is
		// never going to happen — out of fuel, or the ingredient was not smeltable after all.
		if (produced != lastResultCount - resultAtStart || menu.getLitProgress() > 0.0f) {
			lastResultCount = produced + resultAtStart;
			stalledTicks = 0;
		} else if (++stalledTicks > STALL_TIMEOUT_TICKS) {
			problem = "it was loaded but never lit";
			phase = Phase.EMPTYING; // take back what went in rather than abandoning it in the furnace
			ticks = 0;
		}
		return ActionState.WORKING;
	}

	/**
	 * Takes everything back out: the result first, then any raw material and fuel left over.
	 *
	 * <p>Pulling the leftovers back is what makes putting whole stacks in acceptable. Without it, a
	 * request to smelt three iron would leave the other twenty-seven sitting in a furnace somewhere,
	 * which is a strange thing to do to somebody's inventory.</p>
	 */
	private ActionState tickEmptying(Minecraft minecraft, LocalPlayer player) {
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			// Already closed; nothing to recover.
			furnace = null;
			phase = null;
			return ActionState.DONE;
		}
		if (ticks % INTERVAL_TICKS != 0) {
			return ActionState.WORKING;
		}

		for (int slot : new int[] { AbstractFurnaceMenu.RESULT_SLOT,
				AbstractFurnaceMenu.INGREDIENT_SLOT, AbstractFurnaceMenu.FUEL_SLOT }) {
			if (!menu.slots.get(slot).getItem().isEmpty()) {
				quickMove(minecraft, player, slot);
				return ActionState.WORKING; // one per pass, then look again
			}
		}

		closeMenu(minecraft);
		furnace = null;
		phase = null;
		return ActionState.DONE;
	}

	// ---------------------------------------------------------------- plumbing

	private static void quickMove(Minecraft minecraft, LocalPlayer player, int slot) {
		minecraft.gameMode.handleContainerInput(player.containerMenu.containerId, slot, 0,
				ContainerInput.QUICK_MOVE, player);
	}

	/**
	 * A menu slot backed by the player's own inventory holding this item, or {@code -1}.
	 *
	 * <p>{@code slot.index} is the position in the <em>menu</em>, not in the container — the menu
	 * rewrites it when the slot is added — which is what {@code handleContainerInput} wants.</p>
	 */
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
