package mcbot.client.api.actions;

import mcbot.client.BotSettings;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * The shared {@code scaffold} argument, for every action that can place blocks to get somewhere.
 *
 * <p>Travelling is not free. Bridging a ravine or pillaring up a cliff spends blocks out of the
 * inventory. Left to itself the bot now spends the cheapest thing it is carrying rather than the
 * first — but cheapest is a guess made from how hard a block is to get back, and it knows nothing
 * about what the trip was for. This argument is how the caller says what may be spent, and the fact
 * that it appears on every action that travels is deliberate: a caller who has to name the throwaway
 * block each time cannot forget that one is being thrown away.</p>
 *
 * <p>It is one argument in five places rather than five arguments, so that "use dirt" means the same
 * thing to {@code goto} as it does to {@code find}, and so the wording the model reads is written
 * once.</p>
 */
final class Scaffold {

	/**
	 * The argument itself.
	 *
	 * <p>Safe as a static field: building a {@link Parameter} touches no game state, which matters
	 * because the schema is assembled off the client thread.</p>
	 */
	static final Parameter PARAMETER = Parameter.optional("scaffold", ParameterType.STRING,
			"Which block to spend on the bridges and pillars the bot builds to get there — a cheap "
					+ "block id such as 'cobblestone' or 'dirt', or 'any' to spend the cheapest block "
					+ "carried, worked out from how hard each is to get back. "
					+ "A named block is never substituted: when it runs out the bot stops building and "
					+ "routes around. The choice sticks until it is changed, so pass it whenever the "
					+ "answer should differ, and say in your reply which block you chose.");

	private Scaffold() {
	}

	/**
	 * Applies the caller's choice.
	 *
	 * <p>Writing it to the setting rather than carrying it along the call is what makes it hold for
	 * the incidental travel of every <em>other</em> action too — walking to a chest also bridges — and
	 * it keeps one visible answer to what the bot is spending, readable with {@code /mcbot set}.</p>
	 *
	 * @return a failure to hand straight back to the caller, or {@code null} when the choice was
	 *         accepted or none was made
	 */
	static ActionResult choose(Arguments arguments) {
		if (!arguments.has("scaffold")) {
			return null;
		}
		String wanted = arguments.getString("scaffold").trim();

		if (wanted.equalsIgnoreCase(BotSettings.ANY_SCAFFOLD)) {
			BotSettings.SCAFFOLD_BLOCK.set(BotSettings.ANY_SCAFFOLD);
			return null;
		}

		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknownBlock(wanted)
					+ " Or pass 'any' to spend whatever is spare.");
		}
		if (!(item instanceof BlockItem)) {
			return ActionResult.failed(wanted + " is not a block, so it cannot be built with. "
					+ "Pick something like 'cobblestone' or 'dirt'.");
		}
		// Checked here rather than at placement time, where the failure would arrive halfway across a
		// ravine. Sand falls, ice melts underfoot, TNT is TNT — none of them hold up a bridge.
		if (!InventoryManager.isBuildingBlock(item.getDefaultInstance())) {
			return ActionResult.failed(wanted + " does not make sound scaffolding — it either falls, "
					+ "moves you about or is not a full block. Try 'cobblestone', 'dirt' or 'netherrack'.");
		}

		BotSettings.SCAFFOLD_BLOCK.set(wanted);
		return null;
	}

	/** A sentence for the reply saying what the journey will cost, always worth appending. */
	static String note() {
		Item chosen = InventoryManager.scaffoldItem();
		return chosen == null
				? " The cheapest block carried may be spent on bridges and pillars along the way."
				: " Bridging and pillaring with " + InventoryManager.scaffoldName() + ".";
	}
}
