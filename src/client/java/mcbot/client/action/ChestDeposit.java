package mcbot.client.action;

import java.util.function.Predicate;

import mcbot.client.BotSettings;
import mcbot.client.control.Steering;
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
 * Empties the haul into a chest.
 *
 * <p>Without this, a mining run ends the moment the inventory fills: the bot carries on breaking
 * blocks whose drops it can no longer pick up. Being able to bank what it has collected is what turns
 * {@code find <ore> true} from a demonstration into something worth leaving running.</p>
 *
 * <p>Runs as a small state machine because opening a container is not instant — the click goes to the
 * server and the menu arrives some ticks later, so the deposit has to wait for it rather than assume
 * it. Everything after that is a shift-click per stack, which is a single container input.</p>
 */
public final class ChestDeposit {

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
	private Predicate<ItemStack> deposit;
	private Phase phase;
	private int ticks;
	private int sinceTransfer;

	public void begin(BlockPos chest, Predicate<ItemStack> deposit) {
		this.chest = chest.immutable();
		this.deposit = deposit;
		this.phase = Phase.OPENING;
		this.ticks = 0;
		this.sinceTransfer = 0;
	}

	public void cancel(Minecraft minecraft) {
		closeMenu(minecraft);
		chest = null;
		phase = null;
	}

	/** The chest being emptied into, or {@code null}. For the in-world display. */
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
		if (player.getEyePosition().distanceTo(centre) > BotSettings.REACH.get()) {
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

		Slot next = nextSlotToDeposit(player);
		if (next == null) {
			closeMenu(minecraft);
			chest = null;
			phase = null;
			return ActionState.DONE;
		}

		// Shift-click: moves the whole stack across without needing to know where it lands.
		minecraft.gameMode.handleContainerInput(
				player.containerMenu.containerId, next.index, 0, ContainerInput.QUICK_MOVE, player);
		return ActionState.WORKING;
	}

	/**
	 * The next player-inventory slot holding something worth banking, or {@code null} when done.
	 *
	 * <p>Slots are identified by which {@link net.minecraft.world.Container} backs them rather than by
	 * index arithmetic. A chest menu lays out the container's slots first and the player's after, but
	 * the split point depends on the chest's size (single, double, barrel), and hard-coding an offset
	 * would quietly deposit into the wrong half for one of them.</p>
	 */
	private Slot nextSlotToDeposit(LocalPlayer player) {
		for (Slot slot : player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory)) {
				continue; // a chest slot, not ours
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && deposit.test(stack)) {
				return slot;
			}
		}
		return null;
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
