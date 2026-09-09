package descant.client.api.actions;

import java.util.List;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.inventory.InventoryManager;
import net.minecraft.world.item.Item;

/**
 * Throws items on the ground.
 *
 * <p>The one action here that destroys something, in the sense that dropped items despawn after five
 * minutes. So it does exactly what it is told and no more: no "drop the junk", no guessing at what
 * is worth keeping. Naming the item is the confirmation.</p>
 */
public final class DropAction implements Action {

	@Override
	public String name() {
		return "drop";
	}

	@Override
	public String description() {
		return "Throw items on the ground to make room. They stay where they fall and disappear after "
				+ "about five minutes, so this loses them — prefer 'deposit' if there is a chest set. "
				+ "Only drops the item you name.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("item", ParameterType.STRING,
						"The item id to throw away, such as 'cobblestone'."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many to throw. Leave it out to throw all of them."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("item");
		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}

		int carried = InventoryManager.count(context.player(), item);
		if (carried == 0) {
			return ActionResult.failed("No " + wanted + " to drop.");
		}

		int count = arguments.getInt("count", Integer.MAX_VALUE);
		if (count <= 0) {
			return ActionResult.failed("count must be at least 1.");
		}
		int dropped = InventoryManager.dropItem(context.minecraft(), context.player(), item, count);

		return ActionResult.ok("Dropped " + dropped + " " + wanted
				+ (dropped < carried ? ", keeping " + (carried - dropped) + "." : ", all of them."));
	}
}
