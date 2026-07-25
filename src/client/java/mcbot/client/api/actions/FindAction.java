package mcbot.client.api.actions;

import java.util.List;
import java.util.Optional;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
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
				+ "kills it), picks up the drops and moves on to the next one, repeating until none are "
				+ "left in range — this is how you gather a resource. With execute=false it simply "
				+ "travels there once and stops.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("target", ParameterType.STRING,
						"A Minecraft block or entity id, such as 'iron_ore', 'oak_log' or 'cow'. "
								+ "The 'minecraft:' prefix is optional."),
				Parameter.optional("execute", ParameterType.BOOLEAN,
						"True to mine or kill it and keep going to the next one; false to just walk "
								+ "there once. Default false."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String raw = arguments.getString("target");
		boolean execute = arguments.getBoolean("execute", false);

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
			boolean found = context.controller().huntFor(
					context.minecraft(), context.player(), block.get(),
					block.get().getName().getString(), execute);
			return found
					? ActionResult.okQuiet((execute ? "Mining " : "Heading to ") + "the nearest " + raw + ".")
					: ActionResult.failedQuiet("No " + raw + " in range. Travel somewhere else and try again.");
		}

		Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
		if (type.isPresent()) {
			boolean found = context.controller().huntForEntity(
					context.minecraft(), context.player(), type.get(),
					type.get().getDescription().getString(), execute);
			return found
					? ActionResult.okQuiet((execute ? "Hunting " : "Heading to ") + "the nearest " + raw + ".")
					: ActionResult.failedQuiet("No " + raw + " nearby. Mobs are only visible within a few "
							+ "hundred blocks, so travel somewhere else and try again.");
		}

		return ActionResult.failed("There is no block or mob called '" + raw + "'.");
	}
}
