package mcbot.client.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
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

import mcbot.client.BotSettings;

/**
 * Talks to Google's Gemini API.
 *
 * <p>A third conversation shape, different from both the others. Gemini has no {@code tool} or
 * {@code assistant} role — there are only {@code user} and {@code model} — and a call's result goes
 * back as a {@code functionResponse} part inside a <em>user</em> turn. More consequentially, calls
 * carry <em>no ids</em>: a result is matched to its call by function name alone. Two calls to the
 * same action in one turn therefore cannot be told apart, which is a limit of the API rather than of
 * this code. The agent runs and reports them in order, which is the closest thing available.</p>
 *
 * <p>Everything blocks. Called from the agent's worker thread, never the client thread.</p>
 */
public final class GeminiProvider implements LlmProvider {

	/** The model id goes in the path, not the body, so this is a template rather than a constant URL. */
	private static final String ENDPOINT =
			"https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

	/** The name the key goes by, in the environment or the keys file — the one Google's tools use. */
	private static final String KEY_VARIABLE = "GEMINI_API_KEY";

	/** Accepted too, because the official SDKs read it as an alternative and people have it set. */
	private static final String FALLBACK_KEY_VARIABLE = "GOOGLE_API_KEY";

	/**
	 * Ceiling on one reply.
	 *
	 * <p>Twice what Claude gets, because on these models the ceiling covers the thinking as well as
	 * the answer. A turn here is a sentence and a function call, but the deliberation before it is
	 * charged against the same number — and a reply cut off at the limit arrives with no parts at all
	 * rather than a truncated call, which is a confusing way to fail.</p>
	 */
	private static final int MAX_TOKENS = 4096;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@Override
	public String describe() {
		return "gemini " + BotSettings.AI_GEMINI_MODEL.get();
	}

	/** The API key. See {@link ApiKeys} for where it is looked for and why never in a setting. */
	private static String apiKey() throws IOException {
		return ApiKeys.require("Gemini",
				"Get one from aistudio.google.com/apikey — it has a free tier that covers this.",
				KEY_VARIABLE, FALLBACK_KEY_VARIABLE);
	}

	@Override
	public Session begin(String systemPrompt, JsonArray tools) {
		return new GeminiSession(systemPrompt, tools);
	}

	private final class GeminiSession implements Session {

		private final JsonObject systemInstruction;
		private final JsonArray tools;

		/** The running conversation: alternating user and model turns, each a list of parts. */
		private final JsonArray contents = new JsonArray();

		GeminiSession(String systemPrompt, JsonArray actionSchema) {
			this.systemInstruction = instruction(systemPrompt);
			this.tools = wrapTools(actionSchema);
		}

		@Override
		public LlmReply say(String text) throws IOException {
			contents.add(turn("user", parts(textPart(text))));
			return exchange();
		}

		@Override
		public LlmReply report(List<ToolOutcome> outcomes) throws IOException {
			// All of a turn's results go in one user turn, in the order the calls were made — the only
			// thing that lines them up with their calls when the same action was used twice.
			JsonArray parts = new JsonArray();
			for (ToolOutcome outcome : outcomes) {
				// The response must be an object, not a bare string, so the sentence is wrapped. The key
				// is arbitrary as far as the API is concerned; the model reads what is inside it.
				JsonObject response = new JsonObject();
				response.addProperty("result", outcome.result());

				JsonObject functionResponse = new JsonObject();
				functionResponse.addProperty("name", outcome.call().name());
				functionResponse.add("response", response);

				JsonObject part = new JsonObject();
				part.add("functionResponse", functionResponse);
				parts.add(part);
			}
			contents.add(turn("user", parts));
			return exchange();
		}

		private LlmReply exchange() throws IOException {
			JsonObject body = new JsonObject();
			body.add("systemInstruction", systemInstruction);
			body.add("contents", contents);
			body.add("tools", tools);

			JsonObject generation = new JsonObject();
			generation.addProperty("temperature", BotSettings.AI_TEMPERATURE.get());
			generation.addProperty("maxOutputTokens", MAX_TOKENS);
			body.add("generationConfig", generation);

			JsonObject reply = post(body);
			JsonArray parts = candidateParts(reply);

			// Echoed back verbatim next turn. Doubly necessary here: as well as preserving part types
			// this code does not know about, the thinking models attach signatures to their parts and
			// reject a later turn whose history has lost them. Rebuilding from the parsed pieces would
			// strip exactly that.
			contents.add(turn("model", parts));

			return new LlmReply(text(parts), parseCalls(parts));
		}

		private JsonObject post(JsonObject body) throws IOException {
			String url = String.format(ENDPOINT, BotSettings.AI_GEMINI_MODEL.get());
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(BotSettings.AI_REQUEST_TIMEOUT.get()))
					.header("content-type", "application/json")
					// In a header rather than the '?key=' query parameter the docs also allow: a URL ends
					// up in logs and error messages, and this one would carry the key with it.
					.header("x-goog-api-key", apiKey())
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (ConnectException | UnknownHostException e) {
				throw new IOException("Can't reach the Gemini API — check the internet connection. "
						+ "'/mcbot set aiProvider local' runs offline.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Gemini.");
			}

			if (response.statusCode() != 200) {
				throw new IOException(explain(response.statusCode(), response.body()));
			}
			try {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (JsonSyntaxException | IllegalStateException e) {
				throw new IOException("Gemini sent something that wasn't JSON: " + brief(response.body()));
			}
		}
	}

	// ---------------------------------------------------------------- parsing

	/**
	 * The parts of the reply, or a readable complaint about why there are none.
	 *
	 * <p>Worth the care because an empty reply is Gemini's way of saying several quite different
	 * things — the prompt was refused, the answer was cut off, the model had nothing to add — and they
	 * are reported in two different places depending on which it was.</p>
	 */
	private static JsonArray candidateParts(JsonObject reply) throws IOException {
		JsonArray candidates = reply.getAsJsonArray("candidates");
		if (candidates == null || candidates.isEmpty()) {
			JsonObject feedback = reply.getAsJsonObject("promptFeedback");
			String blocked = feedback == null ? null : string(feedback, "blockReason");
			throw new IOException(blocked == null
					? "Gemini sent a reply with no content in it."
					: "Gemini refused the prompt (" + blocked + ").");
		}

		JsonObject candidate = candidates.get(0).getAsJsonObject();
		JsonObject content = candidate.getAsJsonObject("content");
		JsonArray parts = content == null ? null : content.getAsJsonArray("parts");
		if (parts != null && !parts.isEmpty()) {
			return parts;
		}

		String finish = string(candidate, "finishReason");
		if ("MAX_TOKENS".equals(finish)) {
			throw new IOException("Gemini ran out of room before it said anything — its thinking used the "
					+ "whole reply budget. A smaller goal, or a model that deliberates less such as "
					+ "gemini-3.5-flash-lite, should get through.");
		}
		if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish)) {
			throw new IOException("Gemini stopped itself answering that (" + finish + ").");
		}
		throw new IOException("Gemini sent an empty reply"
				+ (finish == null ? "." : " (" + finish + ")."));
	}

	/** Everything the model said in words, with the calls and any private thinking left out. */
	private static String text(JsonArray parts) {
		StringBuilder text = new StringBuilder();
		for (JsonElement element : parts) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject part = element.getAsJsonObject();
			// A thought part is the model's own deliberation and is shown to nobody. It has to be kept in
			// the history — hence the verbatim echo — but repeating it in chat would bury the answer.
			if (!part.has("text") || isTrue(part, "thought")) {
				continue;
			}
			text.append(text.isEmpty() ? "" : " ").append(part.get("text").getAsString().trim());
		}
		return text.toString().trim();
	}

	/**
	 * Pulls the calls out of the parts.
	 *
	 * <p>Argument values are flattened to strings whatever they arrived as, exactly as in the other two
	 * providers and for the same reason: the action layer parses text anyway, so normalising here keeps
	 * a model's inconsistency about quoting numbers from becoming a second thing to handle.</p>
	 *
	 * <p>The id is left empty. Gemini does not issue one, and {@link ToolCall} already treats that as
	 * the normal case for a provider that matches results by name.</p>
	 */
	private static List<ToolCall> parseCalls(JsonArray parts) {
		List<ToolCall> calls = new ArrayList<>();
		for (JsonElement element : parts) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject call = element.getAsJsonObject().getAsJsonObject("functionCall");
			if (call == null || !call.has("name")) {
				continue;
			}

			Map<String, String> arguments = new LinkedHashMap<>();
			JsonElement given = call.get("args");
			if (given != null && given.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : given.getAsJsonObject().entrySet()) {
					JsonElement value = entry.getValue();
					if (value != null && !value.isJsonNull()) {
						arguments.put(entry.getKey(), value.isJsonPrimitive()
								? value.getAsString()
								: value.toString());
					}
				}
			}

			calls.add(new ToolCall("", call.get("name").getAsString(), arguments));
		}
		return calls;
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static boolean isTrue(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsBoolean();
	}

	// ---------------------------------------------------------------- request shaping

	private static JsonObject turn(String role, JsonArray parts) {
		JsonObject content = new JsonObject();
		content.addProperty("role", role);
		content.add("parts", parts);
		return content;
	}

	private static JsonObject instruction(String systemPrompt) {
		JsonObject instruction = new JsonObject();
		instruction.add("parts", parts(textPart(systemPrompt)));
		return instruction;
	}

	private static JsonObject textPart(String text) {
		JsonObject part = new JsonObject();
		part.addProperty("text", text);
		return part;
	}

	private static JsonArray parts(JsonObject part) {
		JsonArray parts = new JsonArray();
		parts.add(part);
		return parts;
	}

	/**
	 * Wraps the neutral action schema in the single {@code functionDeclarations} tool Gemini expects.
	 *
	 * <p>The schema objects go across untouched — {@code type}, {@code description} and {@code enum}
	 * are all in the subset of JSON Schema Gemini accepts, so keeping
	 * {@link mcbot.client.api.ActionRegistry#schema()} minimal pays off a third time.</p>
	 *
	 * <p>With one exception: an action that takes no arguments must have no {@code parameters} at all.
	 * An empty {@code properties} object is rejected outright, so {@code status} and {@code stop} would
	 * take the whole request down with them — every action unusable because two of them are simple.</p>
	 */
	private static JsonArray wrapTools(JsonArray actionSchema) {
		JsonArray declarations = new JsonArray();
		for (JsonElement action : actionSchema) {
			JsonObject source = action.getAsJsonObject();
			JsonObject declaration = new JsonObject();
			declaration.add("name", source.get("name"));
			declaration.add("description", source.get("description"));

			JsonObject parameters = source.getAsJsonObject("parameters");
			JsonObject properties = parameters == null ? null : parameters.getAsJsonObject("properties");
			if (properties != null && !properties.isEmpty()) {
				declaration.add("parameters", parameters);
			}
			declarations.add(declaration);
		}

		JsonObject tool = new JsonObject();
		tool.add("functionDeclarations", declarations);
		JsonArray tools = new JsonArray();
		tools.add(tool);
		return tools;
	}

	// ---------------------------------------------------------------- errors

	/**
	 * Turns an HTTP failure into something actionable.
	 *
	 * <p>Same reasoning as the other two: every one of these has a one-line fix that the status code
	 * alone does not hint at. Gemini leans on 400 for several unrelated problems, so the message text
	 * decides more here than the number does.</p>
	 */
	private static String explain(int status, String body) {
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		String model = BotSettings.AI_GEMINI_MODEL.get();

		if (lower.contains("api key not valid") || lower.contains("api_key_invalid")
				|| status == 401) {
			return "Google rejected the API key. Check the " + KEY_VARIABLE + " in " + ApiKeys.file()
					+ " (or in the environment) holds a current key from aistudio.google.com/apikey.";
		}
		if (lower.contains("api has not been used") || lower.contains("service_disabled")) {
			return "The Generative Language API is not enabled for that key's project. A key made at "
					+ "aistudio.google.com/apikey has it on already; one made in the Cloud console needs "
					+ "it switching on.";
		}
		if (status == 403) {
			return "That key is not allowed to use " + model + ". Check the model name and what the "
					+ "key's project permits.";
		}
		if (status == 404 || lower.contains("is not found for api version")) {
			return "Google has no model called " + model + " on this API version. Check the id — "
					+ "'/mcbot set aiGeminiModel gemini-3.6-flash' — and see ai.google.dev/gemini-api/docs/models.";
		}
		if (status == 429 || lower.contains("resource_exhausted")) {
			return "Out of Gemini quota for " + model + " — the free tier's limits reset, per minute and "
					+ "per day. Wait a little, or switch with '/mcbot set aiProvider local'.";
		}
		if (status == 503 || lower.contains("unavailable") || lower.contains("overloaded")) {
			return "Gemini is overloaded right now. Try again shortly.";
		}
		if (status >= 500) {
			return "Google returned a server error (HTTP " + status + "). Not your end; try again.";
		}
		return "Google returned HTTP " + status + ": " + brief(body);
	}

	private static String brief(String body) {
		if (body == null || body.isBlank()) {
			return "(no detail)";
		}
		String trimmed = body.strip();
		return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
	}
}
