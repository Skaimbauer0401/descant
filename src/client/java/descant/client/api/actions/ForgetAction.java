package descant.client.api.actions;

import java.util.List;
import java.util.stream.Collectors;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.api.ParameterType;
import descant.client.memory.Place;
import descant.client.memory.Places;

/**
 * Removes a place from the bot's memory.
 *
 * <p>{@code name='noticed'} is the useful bulk case: the automatic sightings are guesses and there
 * will be a lot of them after a long trip, whereas the places somebody named are the ones worth
 * keeping. Clearing one without the other is the only sweep worth having.</p>
 */
public final class ForgetAction implements Action {

	/** The word that means "everything the bot worked out for itself". */
	private static final String NOTICED = "noticed";

	@Override
	public String name() {
		return "forget";
	}

	@Override
	public String description() {
		return "Remove a remembered place by name. Pass name='" + NOTICED + "' to clear everything the "
				+ "bot noticed by itself — strongholds, villages, spawners and the rest — while keeping "
				+ "every place that was named deliberately.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.required("name", ParameterType.STRING,
				"The name to forget, or '" + NOTICED + "' for everything the bot noticed itself."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String name = arguments.getString("name").trim();

		if (name.equalsIgnoreCase(NOTICED)) {
			int removed = Places.forgetAutomatic(context.minecraft());
			return ActionResult.ok(removed == 0
					? "Nothing the bot noticed itself to forget."
					: "Forgot " + removed + " place" + (removed == 1 ? "" : "s")
							+ " the bot had noticed. What was named by hand is untouched.");
		}

		if (Places.forget(context.minecraft(), name)) {
			return ActionResult.ok("Forgot " + name + ".");
		}
		// Naming what is left rather than a bare "no": the usual reason is a near miss, and the answer
		// to it is the list.
		List<Place> remaining = Places.here(context.minecraft());
		return ActionResult.failed("Nothing remembered called '" + name + "'. Known here: "
				+ (remaining.isEmpty() ? "nothing"
						: remaining.stream().map(Place::name).collect(Collectors.joining(", "))));
	}
}
