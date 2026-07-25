package mcbot.client.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Looks recipes up by what they produce.
 *
 * <p>Everything here comes from the <b>client's recipe book</b>, which the server fills in as recipes
 * are unlocked. That has a consequence worth stating: the bot can only craft what the player has
 * already unlocked, exactly as a person clicking the recipe book could. It is not a way to craft
 * things you have not discovered.</p>
 *
 * <p>Using the recipe book rather than placing ingredients into grid slots by hand is what makes this
 * tractable at all — one packet lays out the whole grid, and the server does the matching, so there
 * is no need to know or model any recipe's shape.</p>
 */
public final class RecipeFinder {

	/** A crafting grid is 2x2 in the inventory; anything larger needs a bench. */
	private static final int INVENTORY_GRID = 2;

	private RecipeFinder() {
	}

	/**
	 * One way of making something.
	 *
	 * @param id         what to send to place this recipe into a grid
	 * @param result     what comes out, including how many per craft
	 * @param needsTable whether the grid has to be a crafting table's 3x3
	 * @param craftable  whether the ingredients are in the inventory right now
	 */
	public record Match(RecipeDisplayId id, ItemStack result, boolean needsTable, boolean craftable) {
	}

	/**
	 * Every crafting recipe producing {@code wanted}, craftable ones first.
	 *
	 * <p>Only grid recipes. Smelting, smithing, stonecutting and the rest live in the same recipe book
	 * but cannot be made in a crafting grid, so including them would produce a recipe that silently
	 * never completes — see {@link #isSmeltedNotCrafted}.</p>
	 */
	public static List<Match> forItem(LocalPlayer player, Item wanted) {
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		StackedItemContents carried = carried(player);
		List<Match> matches = new ArrayList<>();

		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				Boolean needsTable = gridSize(entry.display());
				if (needsTable == null) {
					continue; // not made in a crafting grid
				}
				ItemStack result = entry.display().result().resolveForFirstStack(context);
				if (result.isEmpty() || !result.is(wanted)) {
					continue;
				}
				matches.add(new Match(entry.id(), result, needsTable, entry.canCraft(carried)));
			}
		}

		// Craftable first: with several ways to make a thing, the one we have the ingredients for is
		// the only one worth reporting first, and a model reads the head of a list.
		matches.sort((a, b) -> Boolean.compare(b.craftable(), a.craftable()));
		return matches;
	}

	/** Whether this item is produced by smelting rather than crafting, for a better refusal. */
	public static boolean isSmeltedNotCrafted(LocalPlayer player, Item wanted) {
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				if (gridSize(entry.display()) != null) {
					continue; // this one *is* a grid recipe
				}
				if (entry.display().result().resolveForFirstStack(context).is(wanted)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether a display is a grid recipe and, if so, whether it needs a bench.
	 *
	 * @return {@code TRUE} needs a 3x3, {@code FALSE} fits the inventory's 2x2, {@code null} is not a
	 *         grid recipe at all
	 */
	private static Boolean gridSize(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return shaped.width() > INVENTORY_GRID || shaped.height() > INVENTORY_GRID;
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() > INVENTORY_GRID * INVENTORY_GRID;
		}
		return null;
	}

	/** The inventory in the form the recipe book uses to decide what can be made. */
	private static StackedItemContents carried(LocalPlayer player) {
		StackedItemContents contents = new StackedItemContents();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			contents.accountSimpleStack(inventory.getItem(slot));
		}
		return contents;
	}
}
