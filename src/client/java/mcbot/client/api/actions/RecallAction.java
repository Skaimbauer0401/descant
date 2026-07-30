package mcbot.client.api.actions;

import java.util.List;
import java.util.stream.Collectors;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.memory.Place;
import mcbot.client.memory.Places;
import net.minecraft.core.BlockPos;

/**
 * Reads the bot's memory of places back out.
 *
 * <p>Nearest first, and only for the world the player is actually in — a base's coordinates from
 * another save are not an answer, they are a trap. Somewhere in another dimension is still listed,
 * because "the fortress is in the nether" answers the question even though you cannot walk there
 * from here; it simply sorts last.</p>
 *
 * <p>Costs nothing and changes nothing, which is the point: a model that has to spend an action to
 * check whether it already knows where the base is will not check, and will go and look for it.</p>
 */
public final class RecallAction implements Action {

	/** How many to list when nobody says. Enough to be a map, few enough to read. */
	private static final int DEFAULT_LIMIT = 12;

	@Override
	public String name() {
		return "recall";
	}

	@Override
	public String description() {
		return "List the places the bot has written down for this world — ones it was told with "
				+ "'remember' and ones it noticed itself, such as strongholds, fortresses, villages "
				+ "and portals — nearest first, with coordinates and distances. Pass 'query' to "
				+ "search by name or kind. Costs nothing and changes nothing, so check here before "
				+ "setting out to look for something: the bot may already know where it is. To go to "
				+ "one, pass its name to goto as 'place'.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(
				Parameter.optional("query", ParameterType.STRING,
						"Optionally, part of a name or kind to search for — 'base', 'portal', "
								+ "'fortress'. Leave it out to list everything."),
				Parameter.optional("count", ParameterType.INTEGER,
						"How many to list at most, nearest first. Defaults to " + DEFAULT_LIMIT + "."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String query = arguments.getString("query", "");
		List<Place> found = Places.matching(context.minecraft(), query);

		if (found.isEmpty()) {
			return ActionResult.ok(query.isBlank()
					? "Nothing remembered in this world yet. Use remember to write somewhere down; the "
							+ "bot also notes strongholds, fortresses, villages and portals as it passes them."
					: "Nothing remembered matching '" + query + "'. Everything known here: "
							+ names(Places.here(context.minecraft())));
		}

		int limit = Math.max(1, arguments.getInt("count", DEFAULT_LIMIT));
		BlockPos from = context.player().blockPosition();
		String dimension = Places.dimensionKey(context.minecraft());

		String listing = found.stream()
				.limit(limit)
				.map(place -> place.describe(from, dimension))
				.collect(Collectors.joining("; "));

		String more = found.size() > limit ? " (" + (found.size() - limit) + " more)" : "";
		return ActionResult.ok(found.size() + " remembered: " + listing + more);
	}

	private static String names(List<Place> places) {
		return places.isEmpty()
				? "none"
				: places.stream().map(Place::name).collect(Collectors.joining(", "));
	}
}
