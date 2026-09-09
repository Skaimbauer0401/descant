package descant.client.action;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import descant.client.BotSettings;
import descant.client.control.Steering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Moves items between the inventory and a chest, in either direction.
 *
 * <p>Putting things in is what turns {@code find <ore> true} from a demonstration into something
 * worth leaving running — without it a mining run ends the moment the inventory fills, and the bot
 * carries on breaking blocks whose drops it can no longer pick up. Taking things back out is the same
 * machinery read backwards, and it is what lets a stored stack become the input to the next job.</p>
 *
 * <p>The two directions differ in exactly one place: which side's slots get shift-clicked. Everything
 * else — opening, pacing, closing — is shared, which is why this is one class with a flag rather than
 * two that would drift apart.</p>
 *
 * <p>Runs as a small state machine because opening a container is not instant: the click goes to the
 * server and the menu arrives some ticks later, so it has to wait rather than assume.</p>
 */
public final class ChestTransfer {

	/** How long to wait for the server to send the container menu before giving up. */
	private static final int OPEN_TIMEOUT_TICKS = 40;

	/** Ticks between shift-clicks, so the transfers do not outrun the server's view of the menu. */
	private static final int TRANSFER_INTERVAL_TICKS = 2;

	private enum Phase {
		/** Facing the chest and clicking it. */
		OPENING,
		/** Menu is up; moving one stack at a time. */
		TRANSFERRING
	}

	private BlockPos chest;
	private Predicate<ItemStack> matching;

	/** {@code true} to take out of the chest, {@code false} to put in. */
	private boolean taking;

	/** Stop once the inventory holds this many, or {@code 0} for everything that matches. */
	private int wanted;

	private Phase phase;
	private int ticks;
	private int sinceTransfer;

	/**
	 * The slot just shift-clicked, and what it held at the time.
	 *
	 * <p>A shift-click into a full chest is not refused, it simply does nothing — and since the stack
	 * then sits exactly where it was, the next pass picks the same slot and clicks it again. That is
	 * what made the bot stand at a full chest clicking forever. Remembering what was clicked is how a
	 * click that achieved nothing gets noticed.</p>
	 */
	private Slot clicked;
	private ItemStack clickedBefore = ItemStack.EMPTY;

	/**
	 * Slots the far side had no room for, by menu index.
	 *
	 * <p>Skipped rather than abandoned on: a chest with one free slot may still take the next stack
	 * even though it could not take this one, and a chest holding 40 cobblestone has room for more
	 * cobblestone while having none for iron.</p>
	 */
	private final Set<Integer> blocked = new HashSet<>();

	/**
	 * @param matching which stacks to move
	 * @param taking   {@code true} to take from the chest, {@code false} to put into it
	 * @param wanted   when taking, stop once the inventory holds this many; {@code 0} takes the lot
	 */
	public void begin(BlockPos chest, Predicate<ItemStack> matching, boolean taking, int wanted) {
		this.chest = chest.immutable();
		this.matching = matching;
		this.taking = taking;
		this.wanted = wanted;
		this.phase = Phase.OPENING;
		this.ticks = 0;
		this.sinceTransfer = 0;
		this.clicked = null;
		this.clickedBefore = ItemStack.EMPTY;
		this.blocked.clear();
	}

	public void cancel(Minecraft minecraft) {
		closeMenu(minecraft);
		chest = null;
		phase = null;
		clicked = null;
		blocked.clear();
	}

	/** The chest being used, or {@code null}. For the in-world display. */
	public BlockPos target() {
		return chest;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player) {
		if (chest == null || minecraft.gameMode == null || minecraft.level == null) {
			return ActionState.FAILED;
		}
		if (++ticks > OPEN_TIMEOUT_TICKS && phase == Phase.OPENING) {
			cancel(minecraft);
			return ActionState.FAILED;
		}

		Vec3 centre = Vec3.atCenterOf(chest);
		// Slack, for the same reason as the crafting bench: the centre of a block is half a block
		// further away than its face, and REACH is sized for breaking blocks, not opening them.
		if (player.getEyePosition().distanceTo(centre) > BotSettings.REACH.get() + 1.5) {
			cancel(minecraft);
			return ActionState.OUT_OF_RANGE;
		}

		return phase == Phase.OPENING
				? tickOpening(minecraft, player, centre)
				: tickTransferring(minecraft, player);
	}

	private ActionState tickOpening(Minecraft minecraft, LocalPlayer player, Vec3 centre) {
		// The menu may already be up from the click we sent last tick.
		if (player.containerMenu != player.inventoryMenu) {
			phase = Phase.TRANSFERRING;
			return ActionState.WORKING;
		}

		Vec3 eye = player.getEyePosition();
		player.setYRot(Steering.approach(player.getYRot(), Steering.yawTowards(player.position(), centre)));
		player.setXRot(Steering.approach(player.getXRot(), Steering.pitchTowards(eye, centre)));

		// Only click every few ticks: spamming right-click on a chest opens and closes it repeatedly,
		// which never settles into a usable menu.
		if (ticks % TRANSFER_INTERVAL_TICKS == 0) {
			Direction face = Direction.getApproximateNearest(
					eye.x - centre.x, eye.y - centre.y, eye.z - centre.z);
			minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(centre, face, chest, false));
			player.swing(InteractionHand.MAIN_HAND);
		}
		return ActionState.WORKING;
	}

	private ActionState tickTransferring(Minecraft minecraft, LocalPlayer player) {
		if (player.containerMenu == player.inventoryMenu) {
			// The chest closed under us — someone else took it, or the server rejected the open.
			cancel(minecraft);
			return ActionState.DONE;
		}
		if (++sinceTransfer < TRANSFER_INTERVAL_TICKS) {
			return ActionState.WORKING;
		}
		sinceTransfer = 0;
		reviewLastClick();

		// Checked before moving anything, so asking for what is already carried moves nothing at all.
		if (taking && wanted > 0 && carried(player) >= wanted) {
			return finish(minecraft);
		}

		Slot next = nextSlot(player);
		if (next == null) {
			// Nothing left that can move. Whether that is "everything went across" or "there was
			// nowhere to put the rest" is the difference between a finished errand and a full chest,
			// and only the blocked list can tell them apart.
			ActionState outcome = blocked.isEmpty() ? ActionState.DONE : ActionState.NO_ROOM;
			finish(minecraft);
			return outcome;
		}

		// Shift-click: moves the whole stack across without needing to know where it lands.
		clicked = next;
		clickedBefore = next.getItem().copy();
		minecraft.gameMode.handleContainerInput(
				player.containerMenu.containerId, next.index, 0, ContainerInput.QUICK_MOVE, player);
		return ActionState.WORKING;
	}

	/**
	 * Marks the last click as blocked if it moved nothing.
	 *
	 * <p>{@code handleContainerInput} applies the move to the client's own menu before sending it, so
	 * the slot already reflects the outcome by the time this runs a tick or two later. Comparing the
	 * whole stack rather than just emptiness is what keeps a <em>partial</em> move — a chest with room
	 * for ten of a stack of sixty-four — counted as progress and retried.</p>
	 */
	private void reviewLastClick() {
		if (clicked == null) {
			return;
		}
		if (ItemStack.matches(clicked.getItem(), clickedBefore)) {
			blocked.add(clicked.index);
		}
		clicked = null;
		clickedBefore = ItemStack.EMPTY;
	}

	private ActionState finish(Minecraft minecraft) {
		closeMenu(minecraft);
		chest = null;
		phase = null;
		return ActionState.DONE;
	}

	/**
	 * The next slot to shift-click, or {@code null} when there is nothing left to move.
	 *
	 * <p>Slots are told apart by which {@link net.minecraft.world.Container} backs them rather than by
	 * index arithmetic. A chest menu lays the container's slots out first and the player's after, but
	 * the split point depends on the chest's size — single, double, barrel — and a hard-coded offset
	 * would quietly work on the wrong half for one of them.</p>
	 *
	 * <p>This one comparison is the entire difference between the two directions.</p>
	 */
	private Slot nextSlot(LocalPlayer player) {
		for (Slot slot : player.containerMenu.slots) {
			boolean ours = slot.container instanceof Inventory;
			if (ours == taking || blocked.contains(slot.index)) {
				continue; // taking wants the chest's slots; putting wants ours
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && matching.test(stack)) {
				return slot;
			}
		}
		return null;
	}

	/**
	 * How many matching items the inventory already holds.
	 *
	 * <p>Only meaningful while taking, and only approximate as a stopping rule: a shift-click moves a
	 * whole stack, so the last one can overshoot. Stopping as soon as there is enough is the honest
	 * behaviour — asking for ten and getting a stack is better than a precise transfer built on the
	 * cursor juggling that already failed once in {@link Smelter}.</p>
	 */
	private int carried(LocalPlayer player) {
		int total = 0;
		for (Slot slot : player.containerMenu.slots) {
			ItemStack stack = slot.getItem();
			if (slot.container instanceof Inventory && !stack.isEmpty() && matching.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Shuts the chest, both server-side and on screen.
	 *
	 * <p>{@code closeContainer} sends the close packet and then calls {@code clientSideCloseContainer},
	 * which is what dismisses the GUI. That matters more than it sounds: opening a chest puts a real
	 * screen in front of the player, and leaving it up would hand them a container window they did not
	 * ask for and swallow their keyboard.</p>
	 */
	private static void closeMenu(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}
}
