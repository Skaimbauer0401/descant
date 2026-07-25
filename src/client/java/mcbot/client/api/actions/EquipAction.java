package mcbot.client.api.actions;

import java.util.List;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.world.item.Item;

/** Puts a named item in the bot's hand. */
public final class EquipAction implements Action {

	@Override
	public String name() {
		return "equip";
	}

	@Override
	public String description() {
		return "Hold a particular item. Rarely needed — the bot picks its own tool for mining, weapon "
				+ "for fighting and food for eating, and picks up the right block to place. Use this "
				+ "when you want something specific in hand regardless.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.required("item", ParameterType.STRING,
				"The item id to hold, such as 'diamond_pickaxe' or 'torch'."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("item");
		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}
		if (!InventoryManager.equipItem(context.minecraft(), context.player(), item)) {
			return ActionResult.failed("No " + wanted + " in the inventory. Call inventory to see what "
					+ "there is.");
		}
		return ActionResult.ok("Holding " + wanted + ".");
	}
}
