package mcbot.client.api.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.inventory.InventoryManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Puts on the best armour the bot is carrying.
 *
 * <p>Its own action rather than four calls to {@code equip}, because armour is worn as a set and
 * picked up as one — a bot that has just looted a chest wants all of it on, and a model that has to
 * name four pieces has four chances to name one that is not there.</p>
 */
public final class ArmourAction implements Action {

	@Override
	public String name() {
		return "armour";
	}

	@Override
	public String description() {
		return "Put on the best armour in the inventory — helmet, chestplate, leggings and boots. Only "
				+ "upgrades: a piece already worn is kept unless something better is carried, so this is "
				+ "safe to call at any time and costs nothing when there is nothing to improve. Call it "
				+ "after looting a chest or crafting armour. Also reports what is worn.";
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		List<String> changed = new ArrayList<>();
		for (EquipmentSlot slot : InventoryManager.ARMOUR_SLOTS) {
			ItemStack put = InventoryManager.wearBestArmour(context.minecraft(), context.player(), slot);
			if (!put.isEmpty()) {
				changed.add(InventoryAction.id(put.getItem()));
			}
		}

		String worn = describeWorn(context);
		if (changed.isEmpty()) {
			// Said plainly rather than as a failure: nothing to improve is a perfectly good outcome, and
			// a model told "failed" here will go looking for a problem that does not exist.
			return ActionResult.ok("Nothing better to put on. " + worn);
		}
		return ActionResult.ok("Put on " + String.join(", ", changed) + ". " + worn);
	}

	/** What is on the body now, so the caller never has to ask a second question to find out. */
	private static String describeWorn(ActionContext context) {
		Map<EquipmentSlot, ItemStack> equipment = InventoryManager.worn(context.player());
		String listed = equipment.entrySet().stream()
				.filter(entry -> !entry.getValue().isEmpty())
				.map(entry -> entry.getKey().getName() + ": " + InventoryAction.id(entry.getValue().getItem()))
				.collect(Collectors.joining(", "));
		return listed.isEmpty() ? "Wearing nothing." : "Wearing " + listed + ".";
	}
}
