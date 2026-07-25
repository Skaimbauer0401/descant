package mcbot.client.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Every action the bot has, looked up by name.
 *
 * <p>Two jobs. It runs actions, turning any way they can fail into a {@link ActionResult} carrying
 * an explanation; and it can describe itself as JSON, which is how a language model is told what
 * the bot is capable of.</p>
 */
public final class ActionRegistry {

	/** Insertion-ordered, so the menu a model sees is stable between runs. */
	private final Map<String, Action> actions = new LinkedHashMap<>();

	public void register(Action action) {
		Action clash = actions.putIfAbsent(action.name().toLowerCase(Locale.ROOT), action);
		if (clash != null) {
			throw new IllegalStateException("Two actions are both called '" + action.name() + "'.");
		}
	}

	/** The action with this name, or {@code null}. */
	public Action get(String name) {
		return actions.get(name.toLowerCase(Locale.ROOT));
	}

	public Collection<Action> all() {
		return actions.values();
	}

	public String names() {
		return actions.keySet().stream().collect(Collectors.joining(", "));
	}

	/**
	 * Runs an action and reports what happened.
	 *
	 * <p>Nothing escapes as an exception. A model driving the bot has to be able to read the outcome
	 * of every call it makes — including the ones it got wrong — because that is how it corrects
	 * itself; a stack trace on the client thread would instead take the whole game down and tell it
	 * nothing.</p>
	 */
	public ActionResult invoke(ActionContext context, String name, Arguments arguments) {
		Action action = get(name);
		if (action == null) {
			return ActionResult.failed(
					"There is no action called '" + name + "'. Available: " + names() + ".");
		}
		try {
			return action.run(context, arguments);
		} catch (ActionException | IllegalArgumentException e) {
			// Expected: the call did not make sense. The message explains what would have.
			return ActionResult.failed(e.getMessage());
		} catch (RuntimeException e) {
			// Unexpected: a bug in the action. Report it rather than killing the client thread, but
			// name the class as well, since "null" on its own has never helped anyone debug anything.
			return ActionResult.failed("'" + name + "' failed unexpectedly: "
					+ e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
		}
	}

	// ---------------------------------------------------------------- describing the menu

	/**
	 * The whole action menu as JSON, for handing to a model.
	 *
	 * <p>Emitted in the plain {@code {name, description, parameters}} shape rather than any one
	 * vendor's wrapper. Anthropic nests this under {@code input_schema} and OpenAI-compatible servers
	 * under {@code function.parameters}, but the object itself is the same in both — so the wrapping
	 * belongs to whichever provider is being spoken to, and this stays neutral.</p>
	 */
	public JsonArray schema() {
		JsonArray tools = new JsonArray();
		for (Action action : actions.values()) {
			JsonObject tool = new JsonObject();
			tool.addProperty("name", action.name());
			tool.addProperty("description", action.description());
			tool.add("parameters", parameterSchema(action));
			tools.add(tool);
		}
		return tools;
	}

	private static JsonObject parameterSchema(Action action) {
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();

		for (Parameter parameter : action.parameters()) {
			JsonObject described = new JsonObject();
			described.addProperty("type", parameter.type().jsonName());
			described.addProperty("description", parameter.description());
			if (!parameter.choices().isEmpty()) {
				JsonArray choices = new JsonArray();
				parameter.choices().forEach(choices::add);
				described.add("enum", choices);
			}
			properties.add(parameter.name(), described);
			if (parameter.required()) {
				required.add(parameter.name());
			}
		}

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		return schema;
	}
}
