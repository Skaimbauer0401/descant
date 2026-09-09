package descant.client.api.actions;

import java.util.List;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.memory.Place;
import descant.client.memory.Places;
import net.minecraft.core.BlockPos;

/**
 * Writes a place down so it can be walked back to later.
 *
 * <p>The deliberate half of memory. What the bot notices by itself is a guess about what it was
 * looking at; this is somebody saying "this is the base", which is a different and better kind of
 * fact — so a named place is never overwritten by a sighting.</p>
 *
 * <p>Defaults to where the bot is standing, because that is what "remember this spot" means and
 * asking for coordinates you are already at is a pointless step.</p>
 */
public final class RememberAction implements Action {

	@Override
	public String name() {
		return "remember";
	}

	@Override
	public String description() {
		return "Write down where something is, under a name, so it can be looked up later with "
				+ "'recall'. Defaults to the bot's current position; "
				+ "pass x, y and z to record somewhere else. Remembered across restarts and kept per "
				+ "world. Use it for anything worth finding again — the base, a portal, a mine, a "
				+ "villager. Naming an existing place again moves it.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.required("name", ParameterType.STRING,
						"What to call it: 'base', 'iron mine', 'nether portal'. This is what 'recall' "
								+ "looks it up by."),
				Parameter.optional("kind", ParameterType.STRING,
						"Optionally what sort of place it is — 'base', 'portal', 'fortress' — which "
								+ "'recall' can then search by. Defaults to the name."),
				Parameter.optional("x", ParameterType.INTEGER, "Where it is, if not right here."),
				Parameter.optional("y", ParameterType.INTEGER, "Where it is, if not right here."),
				Parameter.optional("z", ParameterType.INTEGER, "Where it is, if not right here."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String name = arguments.getString("name").trim();
		if (name.isEmpty()) {
			return ActionResult.failed("Give it a name, e.g. remember name='base'.");
		}

		boolean anyCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
		if (anyCoordinate && !(arguments.has("x") && arguments.has("y") && arguments.has("z"))) {
			// Half a coordinate is worse than none: filling the rest in from where the bot happens to
			// stand would write down somewhere nobody meant.
			return ActionResult.failed("Give all three of x, y and z, or none of them to use where the "
					+ "bot is standing.");
		}

		BlockPos pos = anyCoordinate
				? new BlockPos(arguments.getInt("x", 0), arguments.getInt("y", 0),
						arguments.getInt("z", 0))
				: context.player().blockPosition();

		Place existing = Places.named(context.minecraft(), name);
		Place place = Places.remember(context.minecraft(), name,
				arguments.getString("kind", name), pos, false);

		return ActionResult.ok((existing == null ? "Remembered " : "Moved ") + place.name()
				+ " — " + place.x() + ", " + place.y() + ", " + place.z()
				+ " in " + place.dimension() + ".");
	}
}
