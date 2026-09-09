package descant.client.action;

import descant.client.BotSettings;
import descant.client.control.BotInput;
import descant.client.inventory.InventoryManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Eats when hunger drops far enough to matter, using food from anywhere in the inventory.
 *
 * <p>Hunger is not cosmetic for a travelling bot: below 7 hunger points the player cannot sprint,
 * which roughly halves travel speed, and at zero they starve. Topping up while walking costs a
 * couple of seconds and avoids both.</p>
 *
 * <p>Eating is a <em>held</em> interaction — {@code useItem} starts it and the item must stay in
 * use for the food's duration. Releasing early wastes the attempt entirely, so this action stands
 * still and keeps the interaction alive until the player stops using the item.</p>
 */
public final class EatAction {

	/** Give up if eating has not completed in this long; something is interrupting it. */
	private static final int TIMEOUT_TICKS = 100;

	/** A full hunger bar, used to work out how much a given food would actually be worth. */
	private static final int FULL_FOOD_LEVEL = 20;

	private boolean started;
	private int ticks;

	/**
	 * Whether the bot should stop and eat right now.
	 *
	 * <p>Two reasons to eat, not one. The obvious is hunger. The less obvious is <em>healing</em>:
	 * natural regeneration only runs at or above {@link BotSettings#REGEN_FOOD_LEVEL} hunger, so a
	 * wounded bot with a half-full hunger bar will never heal until it tops up. Eating is the only
	 * healing mechanism the bot has short of a golden apple.</p>
	 */
	public static boolean shouldEat(LocalPlayer player) {
		// Badly hurt: spend a golden apple, if there is one.
		if (needsEmergencyHeal(player)) {
			return true;
		}
		// Everything below is about ordinary hunger, which must never be answered with an emergency
		// food. Checking hasFood() here instead would let a bot carrying only golden apples decide
		// forever that it should eat and then refuse to — standing still, exactly the reported bug.
		if (!InventoryManager.hasEdibleFood(player)) {
			return false;
		}
		int foodLevel = player.getFoodData().getFoodLevel();
		if (foodLevel <= BotSettings.EAT_BELOW_FOOD_LEVEL.get()) {
			return true;
		}
		boolean wounded = player.getMaxHealth() - player.getHealth() >= BotSettings.HEAL_BY_EATING_THRESHOLD.getFloat();
		return wounded && foodLevel < BotSettings.REGEN_FOOD_LEVEL;
	}

	/** Health is low enough that spending a golden apple is the right call. */
	private static boolean needsEmergencyHeal(LocalPlayer player) {
		return player.getHealth() <= BotSettings.EMERGENCY_HEAL_HEALTH.getFloat()
				&& InventoryManager.hasEmergencyFood(player);
	}

	public void begin() {
		started = false;
		ticks = 0;
	}

	public void cancel(Minecraft minecraft) {
		// Always release the key, even if we never started: leaving it stuck down would make the
		// player right-click everything they walked past.
		minecraft.options.keyUse.setDown(false);
		if (started && minecraft.gameMode != null && minecraft.player != null) {
			minecraft.gameMode.releaseUsingItem(minecraft.player);
		}
		started = false;
		ticks = 0;
	}

	public ActionState tick(Minecraft minecraft, LocalPlayer player, BotInput input) {
		if (minecraft.gameMode == null) {
			return ActionState.FAILED;
		}

		// Stand still: walking is fine while eating, but stopping keeps us on the path node we
		// planned from, so the route does not drift while we are not steering.
		input.forward(false).backward(false).left(false).right(false).sprint(false);

		if (started && !shouldEat(player) && !player.isUsingItem()) {
			cancel(minecraft);
			return ActionState.DONE;
		}
		if (++ticks > TIMEOUT_TICKS) {
			cancel(minecraft);
			return ActionState.FAILED;
		}

		boolean emergency = needsEmergencyHeal(player);

		// Equip food only when not already holding the right thing.
		//
		// This is the fix for "the bot stands there and never eats". Re-equipping every tick is not
		// harmless: when the chosen food lives in the main inventory rather than the hotbar,
		// equipping performs a container swap, and a swap every tick keeps *restarting* the item
		// use, so the eat never finishes. It then times out after five seconds, the bot walks on a
		// little (dropping the hunger that made the timing look threshold-dependent), and tries
		// again. Equipping once and leaving the stack alone lets the use run to completion.
		ItemStack held = player.getMainHandItem();
		boolean holdingRightFood = emergency
				? InventoryManager.isEmergencyFood(held)
				: InventoryManager.isFood(held) && !InventoryManager.isEmergencyFood(held);

		if (!holdingRightFood) {
			int missing = FULL_FOOD_LEVEL - player.getFoodData().getFoodLevel();
			boolean equipped = emergency
					? InventoryManager.equipEmergencyFood(minecraft, player)
					: InventoryManager.equipBestFood(minecraft, player, missing);
			if (!equipped) {
				cancel(minecraft);
				return ActionState.NO_MATERIAL;
			}
			// Let the swap settle for a tick before asserting the use, so vanilla sees a stable
			// stack in hand rather than one that changed under it.
			return ActionState.WORKING;
		}

		// Hold the vanilla use key rather than calling useItem ourselves.
		//
		// Eating is a held interaction, and Minecraft.handleKeybinds runs
		// `if (!keyUse.isDown()) gameMode.releaseUsingItem(player)` every single tick. A one-shot
		// useItem call is therefore cancelled on the very next tick and the food is never consumed.
		// With the key held down, vanilla starts the use itself and keeps it alive to completion.
		minecraft.options.keyUse.setDown(true);
		started = true;
		return ActionState.WORKING;
	}
}
