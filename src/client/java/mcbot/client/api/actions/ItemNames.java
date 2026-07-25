package mcbot.client.api.actions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Turns the names people and models write into registry entries.
 *
 * <p>Shared so that every action forgives the same things and complains the same way. The
 * {@code minecraft:} prefix is optional, because it is noise that a model will sometimes include and
 * sometimes not, and there is no reading of "furnace" that means anything else.</p>
 */
final class ItemNames {

	private ItemNames() {
	}

	/** The item with this id, or {@code null} if there is no such thing. */
	static Item resolve(String name) {
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name.trim());
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		// An unknown id resolves to AIR rather than to nothing, so without this every typo would
		// silently become a valid request to equip or drop empty space.
		return item == null || item == Items.AIR ? null : item;
	}

	static String unknown(String name) {
		return "There is no item called '" + name + "'. Use a Minecraft id like 'furnace', "
				+ "'oak_planks' or 'diamond_pickaxe'.";
	}
}
