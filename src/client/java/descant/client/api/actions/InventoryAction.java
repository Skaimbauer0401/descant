package descant.client.api.actions;

import java.util.Map;
import java.util.stream.Collectors;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.inventory.InventoryManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Lists what the bot is carrying.
 *
 * <p>The most useful read-only action there is, and the one whose absence was most limiting: without
 * it a model could see that the inventory was 80% full but not what was in it, so it could not
 * decide to place a furnace it already had, drop the cobble it did not want, or notice it had no
 * pickaxe. Perception has to come before choice.</p>
 */
public final class InventoryAction implements Action {

	/** Items to name individually before the list is cut short. */
	private static final int LISTED = 24;

	@Override
	public String name() {
		return "inventory";
	}

	@Override
	public String description() {
		return "List everything the bot is carrying, with counts, and what is in its hand. Costs "
				+ "nothing and changes nothing. Call it before deciding what to place, drop or use — "
				+ "the item ids it returns are the ones the other actions expect.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		Map<Item, Integer> contents = InventoryManager.contents(context.player());
		String equipment = describeEquipment(context);

		if (contents.isEmpty()) {
			return ActionResult.ok("Carrying nothing at all. " + equipment);
		}

		String listed = contents.entrySet().stream()
				.limit(LISTED)
				.map(entry -> id(entry.getKey()) + (entry.getValue() > 1 ? " x" + entry.getValue() : ""))
				.collect(Collectors.joining(", "));
		if (contents.size() > LISTED) {
			listed += ", and " + (contents.size() - LISTED) + " other kinds";
		}

		ItemStack held = context.player().getMainHandItem();
		return ActionResult.ok("Carrying: " + listed
				+ " | holding: " + (held.isEmpty() ? "nothing" : id(held.getItem()))
				+ " | " + equipment
				+ " | " + Math.round(InventoryManager.fullness(context.player()) * 100) + "% of slots used");
	}

	/**
	 * What is worn and off-handed.
	 *
	 * <p>Reported here because worn armour is <em>not</em> in the inventory — the equipment slots are a
	 * separate container — so without this a piece of armour vanishes from the bot's own account of
	 * itself the moment it is put on, and the model concludes it was lost.</p>
	 */
	private static String describeEquipment(ActionContext context) {
		String listed = InventoryManager.worn(context.player()).entrySet().stream()
				.filter(entry -> !entry.getValue().isEmpty())
				.map(entry -> entry.getKey().getName() + ": " + id(entry.getValue().getItem()))
				.collect(Collectors.joining(", "));
		return listed.isEmpty() ? "wearing nothing" : "wearing " + listed;
	}

	/**
	 * The registry id, without the {@code minecraft:} prefix.
	 *
	 * <p>Deliberately not the display name. "Block of Iron" is what a person reads, but every other
	 * action takes an id, and a model handed one form and asked for the other will hand back what it
	 * was given.</p>
	 */
	static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}
}
