package mcbot.client.api.actions;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.control.TravelMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Goes to the nearest block or mob of a named kind, optionally mining or killing it.
 *
 * <p>One action for both because from the outside they are the same request — "go find me some
 * iron_ore", "go find a cow" — and which registry the name lives in is a detail of how Minecraft
 * happens to be organised, not something worth making the caller work out first.</p>
 */
public final class FindAction implements Action {

	@Override
	public String name() {
		return "find";
	}

	@Override
	public String description() {
		return "Go to the nearest block or mob of a given kind. With execute=true the bot mines it (or "
				+ "kills it), picks up the drops and moves on to the next one — this is how you gather a "
				+ "resource. Give a count to stop after that many; without one it keeps going until "
				+ "there are none left in range, which for a common block may be thousands of them. "
				+ "With execute=false it simply travels there once and stops. "
				+ "BE AWARE this is blunt about how it gets there: it takes the shortest route to the "
				+ "nearest one and tunnels through whatever is in the way, including digging straight "
				+ "down. It will not mine into lava it can see, take a killing fall, or break anything "
				+ "that was built — chests, furnaces, crafting tables, beds and signs are routed "
				+ "around — but it will gladly bore a hole through a wall or a floor, break into a "
				+ "cave full of mobs, or spend a long time underground. "
				+ "By default it breaks and places on the way, bridging gaps and pillaring up on blocks "
				+ "out of its own inventory, so say which block to spend with 'scaffold' — or pass "
				+ "travel='walk' to make the journey between targets movement-only. Note that mining the "
				+ "target itself always happens with execute=true, whatever travel says: travel is about "
				+ "getting there, not about the job. "
				+ "If any of that would matter, check the surroundings with look or locate first.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("target", ParameterType.STRING,
						"A Minecraft block or entity id, such as 'iron_ore', 'oak_log' or 'cow'. "
								+ "The 'minecraft:' prefix is optional."),
				Parameter.optional("execute", ParameterType.BOOLEAN,
						"True to mine or kill it and keep going to the next one; false to just walk "
								+ "there once. Default false."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many to mine or kill before stopping. Needs execute=true. Counts blocks "
								+ "broken and mobs killed, not items collected, so allow for a block "
								+ "sometimes dropping more than one. Leave it out to clear every one in "
								+ "range, which on a common ore can take a very long time."),
				Travel.PARAMETER,
				Scaffold.PARAMETER);
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String raw = arguments.getString("target");
		boolean execute = arguments.getBoolean("execute", false);
		int count = arguments.getInt("count", 0);
		if (count > 0 && !execute) {
			return ActionResult.failed("A count needs execute=true — without it the bot only walks to "
					+ "the nearest one and never gathers any.");
		}

		ActionResult rejected = Scaffold.choose(arguments);
		if (rejected != null) {
			return rejected;
		}
		// Building by default: gathering is the one job where digging is the point rather than a
		// side effect, so find keeps its old behaviour unless it is told otherwise.
		TravelMode mode = Travel.mode(arguments, TravelMode.BUILD);

		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null) {
			return ActionResult.failed("'" + raw + "' is not a valid Minecraft id. "
					+ "Use a name like 'iron_ore' or 'cow'.");
		}

		// Blocks first, then mobs: the two registries do not overlap, and blocks are the common ask.
		// The air check exists because an unknown id resolves to air rather than to nothing, so
		// without it every typo would silently become a hunt for empty space.
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
		if (block.isPresent() && (block.get() != Blocks.AIR || raw.endsWith("air"))) {
			// Ores come in stone-type variants, and asking for diamond_ore below y=0 would otherwise
			// find nothing at the exact depth where every diamond in the world is.
			Set<Block> family = BlockFamily.of(block.get());
			boolean found = context.controller().huntFor(
					context.minecraft(), context.player(), family,
					BlockFamily.describe(family), execute, mode);
			if (!found) {
				return ActionResult.failedQuiet("No " + raw + " in range. Travel somewhere else and try again.");
			}
			context.controller().setHuntQuota(count);
			return ActionResult.okQuiet(describe(execute, count, raw, mode));
		}

		Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
		if (type.isPresent()) {
			boolean found = context.controller().huntForEntity(
					context.minecraft(), context.player(), type.get(),
					type.get().getDescription().getString(), execute, mode);
			if (!found) {
				return ActionResult.failedQuiet("No " + raw + " nearby. Mobs are only visible within a few "
						+ "hundred blocks, so travel somewhere else and try again.");
			}
			context.controller().setHuntQuota(count);
			return ActionResult.okQuiet(describe(execute, count, raw, mode));
		}

		return ActionResult.failed("There is no block or mob called '" + raw + "'.");
	}

	private static String describe(boolean execute, int count, String target, TravelMode mode) {
		if (!execute) {
			return "Heading to the nearest " + target + "." + Travel.note(mode);
		}
		return (count > 0
				? "Gathering " + count + " " + target + "."
				: "Gathering every " + target + " in range.") + Travel.note(mode);
	}
}
