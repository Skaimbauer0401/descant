package descant.client.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import descant.client.BotSettings;

/**
 * Talks to anything that speaks OpenAI's chat-completions API.
 *
 * <p>Which, as it turns out, is most things: OpenAI's own models, Alibaba's Qwen through DashScope's
 * compatible endpoint, and Moonshot's Kimi. Three vendors, one wire format, and the differences
 * between them — the address, the name the key goes by, which setting holds the model — all live on
 * {@link AiProvider} rather than here. Adding a fourth is a constant there and nothing here.</p>
 *
 * <p>Not the same shape as Ollama's, despite the family resemblance. Three differences matter and
 * each one silently breaks the second turn rather than the first: the reply is under
 * {@code choices[0].message} instead of {@code message}, a tool result is matched by
 * {@code tool_call_id} rather than by name, and {@code function.arguments} arrives as <em>a string
 * containing JSON</em> rather than as an object. That last one is the classic — parsing it as an
 * object gets an empty argument map and a bot that walks to (0, 0, 0).</p>
 *
 * <p>Everything blocks. Called from the agent's worker thread, never the client thread.</p>
 */
public final class OpenAiProvider implements LlmProvider {

	private final AiProvider flavour;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	public OpenAiProvider(AiProvider flavour) {
		this.flavour = flavour;
	}

	@Override
	public String describe() {
		return flavour.key() + " " + model();
	}

	private String model() {
		return flavour.modelSetting().get();
	}

	/** The API key. See {@link ApiKeys} for where it is looked for and why never in a setting. */
	private String apiKey() throws IOException {
		return ApiKeys.require(flavour.label(), flavour.keyHelp(), flavour.keyNames());
	}

	@Override
	public Session begin(String systemPrompt, JsonArray tools) {
		return new OpenAiSession(systemPrompt, tools);
	}

	private final class OpenAiSession implements Session {

		/** The running conversation, in the API's own shape — kept verbatim so nothing is lost. */
		private final JsonArray messages = new JsonArray();
		private final JsonArray tools;

		OpenAiSession(String systemPrompt, JsonArray actionSchema) {
			this.tools = wrapTools(actionSchema);
			messages.add(message("system", systemPrompt));
		}

		@Override
		public LlmReply say(String text) throws IOException {
			messages.add(message("user", text));
			return exchange();
		}

		@Override
		public LlmReply report(List<ToolOutcome> outcomes) throws IOException {
			for (ToolOutcome outcome : outcomes) {
				JsonObject result = message("tool", outcome.result());
				// By id, not by name. Two calls to the same action in one turn are told apart here and
				// nowhere else — matching by name would pair them up wrongly half the time.
				result.addProperty("tool_call_id", outcome.call().id());
				messages.add(result);
			}
			return exchange();
		}

		private LlmReply exchange() throws IOException {
			JsonObject body = new JsonObject();
			body.addProperty("model", model());
			body.add("messages", messages);
			body.add("tools", tools);
			// Low temperature. Choosing the right function from a list is not a task that benefits from
			// invention, and a model at default heat will cheerfully make up an action.
			body.addProperty("temperature", BotSettings.AI_TEMPERATURE.get());

			JsonObject reply = post("/chat/completions", body);
			JsonArray choices = reply.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
				throw new IOException(flavour.label() + " sent a reply with no choices in it.");
			}
			JsonObject assistant = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (assistant == null) {
				throw new IOException(flavour.label() + " sent a choice with no message in it.");
			}

			// Echoed back verbatim on the next turn, tool calls and all, so the model can see what it
			// already decided. Rebuilding it from the parsed pieces would quietly drop whatever we had
			// not thought to parse — and on the reasoning models that includes fields they check.
			messages.add(assistant);

			return new LlmReply(text(assistant), parseCalls(assistant));
		}

		private JsonObject post(String path, JsonObject body) throws IOException {
			String url = flavour.baseUrl() + path;
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(BotSettings.AI_REQUEST_TIMEOUT.get()))
					.header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + apiKey())
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (ConnectException e) {
				throw new IOException("Can't reach " + flavour.label() + " at " + flavour.baseUrl()
						+ ". Check the connection.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for " + model() + ".");
			}

			if (response.statusCode() != 200) {
				throw new IOException(explain(response.statusCode(), response.body()));
			}
			try {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (JsonSyntaxException | IllegalStateException e) {
				throw new IOException(flavour.label() + " sent something that wasn't JSON: "
						+ brief(response.body()));
			}
		}
	}

	// ---------------------------------------------------------------- parsing

	private static String text(JsonObject assistant) {
		JsonElement content = assistant.get("content");
		return content == null || content.isJsonNull() ? "" : content.getAsString().trim();
	}

	/**
	 * Pulls the calls out of a reply.
	 *
	 * <p>{@code function.arguments} is <b>a string containing JSON</b>, not an object — the one place
	 * this API is genuinely awkward, and the one a reader coming from Ollama's shape will get wrong.
	 * A model that emits malformed JSON in there is common enough to be worth surviving: the call is
	 * kept with no arguments so the action layer can complain about what is missing, which the model
	 * can act on, rather than the whole turn failing to parse.</p>
	 */
	private static List<ToolCall> parseCalls(JsonObject assistant) {
		List<ToolCall> calls = new ArrayList<>();
		JsonArray raw = assistant.getAsJsonArray("tool_calls");
		if (raw == null) {
			return calls;
		}

		for (JsonElement element : raw) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject call = element.getAsJsonObject();
			JsonObject function = call.getAsJsonObject("function");
			if (function == null || !function.has("name")) {
				continue;
			}

			JsonElement id = call.get("id");
			calls.add(new ToolCall(
					id == null || id.isJsonNull() ? "" : id.getAsString(),
					function.get("name").getAsString(),
					parseArguments(function.get("arguments"))));
		}
		return calls;
	}

	/**
	 * Flattens the argument object to strings, whatever the values arrived as.
	 *
	 * <p>Models are inconsistent about whether a coordinate is a number or a quoted number, sometimes
	 * within one reply; the action layer parses text anyway, so normalising here stops that variation
	 * becoming a second thing to handle.</p>
	 */
	private static Map<String, String> parseArguments(JsonElement given) {
		Map<String, String> arguments = new LinkedHashMap<>();
		if (given == null || given.isJsonNull()) {
			return arguments;
		}

		JsonObject object;
		try {
			// A string in the normal case, but some compatible endpoints send the object directly. Both
			// are accepted rather than one being declared correct — the cost is two lines.
			object = given.isJsonPrimitive()
					? JsonParser.parseString(given.getAsString()).getAsJsonObject()
					: given.getAsJsonObject();
		} catch (JsonSyntaxException | IllegalStateException e) {
			return arguments;
		}

		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			JsonElement value = entry.getValue();
			if (value != null && !value.isJsonNull()) {
				arguments.put(entry.getKey(),
						value.isJsonPrimitive() ? value.getAsString() : value.toString());
			}
		}
		return arguments;
	}

	// ---------------------------------------------------------------- request shaping

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	/**
	 * Wraps the neutral action schema in the envelope this API expects.
	 *
	 * <p>The same envelope Ollama uses, which is not a coincidence — Ollama copied it. This is the
	 * whole reason {@link descant.client.api.ActionRegistry#schema()} stays vendor-neutral.</p>
	 */
	private static JsonArray wrapTools(JsonArray actionSchema) {
		JsonArray tools = new JsonArray();
		for (JsonElement action : actionSchema) {
			JsonObject tool = new JsonObject();
			tool.addProperty("type", "function");
			tool.add("function", action);
			tools.add(tool);
		}
		return tools;
	}

	// ---------------------------------------------------------------- errors

	/**
	 * Turns an HTTP failure into something actionable.
	 *
	 * <p>Every one of these is a first-run problem with a one-line fix, and the raw status hints at
	 * none of them. The model id is always named: it is the thing most likely to be wrong, and an
	 * error that omits it is how a whole afternoon goes on the wrong setting.</p>
	 */
	private String explain(int status, String body) {
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		String keyName = flavour.keyNames().getFirst();

		if (status == 401 || status == 403 || lower.contains("invalid_api_key")
				|| lower.contains("incorrect api key")) {
			return flavour.label() + " rejected the API key. Check the " + keyName + " in "
					+ ApiKeys.file() + " (or in the environment) is current and has not been revoked. "
					+ flavour.keyHelp();
		}
		if (status == 404 || lower.contains("model_not_found") || lower.contains("does not exist")) {
			return flavour.label() + " has no model called '" + model() + "'. Open the settings screen "
					+ "(G) and pick one from the list it fetches, which is what your key can reach.";
		}
		if (lower.contains("does not support tools") || lower.contains("tools is not supported")
				|| lower.contains("function calling")) {
			return model() + " can't call tools, so it can't drive the bot. Pick a model that supports "
					+ "function calling — most current ones do, but the reasoning-only and audio "
					+ "variants often do not.";
		}
		if (status == 429 || lower.contains("rate limit") || lower.contains("quota")) {
			return "Out of allowance on " + flavour.label() + " for " + model()
					+ ". Either the per-minute rate limit or the account balance — the message says "
					+ "which: " + brief(body) + " '/descant set aiProvider ollama-local' works offline meanwhile.";
		}
		if (status == 402 || lower.contains("insufficient") || lower.contains("billing")) {
			return flavour.label() + " says the account has no credit left. Top it up, or use "
					+ "'/descant set aiProvider ollama-local' meanwhile.";
		}
		return flavour.label() + " returned HTTP " + status + ": " + brief(body);
	}

	private static String brief(String body) {
		if (body == null || body.isBlank()) {
			return "(no detail)";
		}
		String trimmed = body.strip();
		return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
	}
}
