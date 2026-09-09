package descant.client.api.actions;

import java.util.List;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.inventory.InventoryManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Puts a named item in the bot's hand, on its body, or in its off hand. */
public final class EquipAction implements Action {

	private static final String AUTO = "auto";
	private static final String HAND = "hand";
	private static final String OFFHAND = "offhand";

	@Override
	public String name() {
		return "equip";
	}

	@Override
	public String description() {
		return "Hold, wear or off-hand a particular item. Rarely needed for tools — the bot picks its "
				+ "own tool for mining, weapon for fighting and food for eating, and picks up the right "
				+ "block to place. Armour and shields are the real use: by default a piece of armour is "
				+ "worn and a shield goes to the off hand, so 'equip iron_chestplate' puts it on rather "
				+ "than in the hand. Use 'armour' instead to put on everything at once.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("item", ParameterType.STRING,
						"The item id, such as 'diamond_pickaxe', 'iron_chestplate', 'shield' or 'torch'."),
				Parameter.choice("where",
						"Where it should go. 'auto' is almost always right: armour is worn, a shield goes "
								+ "to the off hand, everything else is held. 'hand' forces it into the main "
								+ "hand even if it is armour, and 'offhand' puts anything in the off hand — "
								+ "useful for keeping torches or blocks there while a tool stays in hand.",
						false, List.of(AUTO, HAND, OFFHAND)));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("item");
		Item item = ItemNames.resolve(wanted);
		if (item == null) {
			return ActionResult.failed(ItemNames.unknown(wanted));
		}

		int carried = InventoryManager.findSlot(context.player(), stack -> stack.is(item));
		if (carried < 0) {
			return ActionResult.failed("No " + wanted + " in the inventory. Call inventory to see what "
					+ "there is.");
		}

		String where = arguments.getString("where", AUTO);
		EquipmentSlot slot = destination(context.player().getInventory().getItem(carried), where);

		if (slot == null) {
			InventoryManager.equipItem(context.minecraft(), context.player(), item);
			return ActionResult.ok("Holding " + wanted + ".");
		}
		if (!InventoryManager.wearItem(context.minecraft(), context.player(), item, slot)) {
			// Reached when the slot refuses the item — asking for leggings in the off hand is fine, but
			// asking to wear a pickaxe is not, and the refusal happens at the slot rather than here.
			return ActionResult.failed("Couldn't put " + wanted + " " + describe(slot)
					+ ". Only armour goes in an armour slot; anything can be held or off-handed.");
		}
		return ActionResult.ok((slot == EquipmentSlot.OFFHAND ? "Off hand: " : "Wearing ") + wanted + ".");
	}

	/**
	 * Which equipment slot this should end up in, or {@code null} for the main hand.
	 *
	 * <p>Auto reads the item's own idea of where it belongs, which is what makes "equip iron_boots"
	 * mean the obvious thing. Deciding otherwise — holding armour because the action is called
	 * "equip" — would be a distinction the caller has no reason to expect and no way to see.</p>
	 */
	private static EquipmentSlot destination(ItemStack stack, String where) {
		return switch (where) {
			case HAND -> null;
			case OFFHAND -> EquipmentSlot.OFFHAND;
			default -> {
				EquipmentSlot worn = InventoryManager.wearSlot(stack);
				yield worn == EquipmentSlot.MAINHAND ? null : worn;
			}
		};
	}

	private static String describe(EquipmentSlot slot) {
		return slot == EquipmentSlot.OFFHAND ? "in the off hand" : "on (" + slot.getName() + ")";
	}
}
