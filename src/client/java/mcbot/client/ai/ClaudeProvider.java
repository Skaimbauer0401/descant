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
 * Talks to Anthropic's Messages API.
 *
 * <p>Structurally different from {@link OllamaProvider} in one way that matters: a Claude
 * conversation is made of <em>content blocks</em>, not messages with a {@code tool_calls} field
 * hanging off them. The model's reply is a list of text and {@code tool_use} blocks, and the results
 * go back as {@code tool_result} blocks inside a <em>user</em> message. That difference is exactly
 * why {@link LlmProvider.Session} owns its own history rather than being handed one — neither shape
 * has to pretend to be the other.</p>
 *
 * <p>Everything blocks. Called from the agent's worker thread, never the client thread.</p>
 */
public final class ClaudeProvider implements LlmProvider {

	private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";

	/** The API version this code is written against. Anthropic requires it on every request. */
	private static final String API_VERSION = "2023-06-01";

	/**
	 * The names the key goes by, in the environment or the keys file, best first.
	 *
	 * <p>Read by {@link AiProvider#keyNames()} as well as here, so that whatever asks "is this
	 * provider usable?" and whatever actually uses it are looking at the same list.</p>
	 */
	static final List<String> KEY_NAMES = List.of("ANTHROPIC_API_KEY");

	/**
	 * Ceiling on one reply.
	 *
	 * <p>Generous for what is being asked. A turn here is a sentence and a function call, so this is
	 * a runaway guard rather than a budget — and since output is the expensive half, it is the number
	 * that bounds what a confused model can cost in one go.</p>
	 */
	private static final int MAX_TOKENS = 2048;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@Override
	public String describe() {
		return "claude " + BotSettings.AI_CLAUDE_MODEL.get();
	}

	/** The API key. See {@link ApiKeys} for where it is looked for and why never in a setting. */
	private static String apiKey() throws IOException {
		return ApiKeys.require("Claude",
				"Create one at console.anthropic.com — note this is the paid API, billed per token, "
						+ "and a Claude Pro subscription does not cover it.",
				KEY_NAMES);
	}

	@Override
	public Session begin(String systemPrompt, JsonArray tools) {
		return new ClaudeSession(systemPrompt, tools);
	}

	private final class ClaudeSession implements Session {

		private final String systemPrompt;
		private final JsonArray tools;

		/** The running conversation: alternating user and assistant messages, each with content blocks. */
		private final JsonArray messages = new JsonArray();

		ClaudeSession(String systemPrompt, JsonArray actionSchema) {
			this.systemPrompt = systemPrompt;
			this.tools = wrapTools(actionSchema);
		}

		@Override
		public LlmReply say(String text) throws IOException {
			messages.add(userMessage(textBlock(text)));
			return exchange();
		}

		@Override
		public LlmReply report(List<ToolOutcome> outcomes) throws IOException {
			// Every result for a turn goes in ONE user message. Anthropic requires the tool_result
			// blocks to be the first content of the message immediately after the tool_use, so splitting
			// them across messages is rejected outright rather than merely discouraged.
			JsonArray blocks = new JsonArray();
			for (ToolOutcome outcome : outcomes) {
				JsonObject result = new JsonObject();
				result.addProperty("type", "tool_result");
				result.addProperty("tool_use_id", outcome.call().id());
				result.addProperty("content", outcome.result());
				blocks.add(result);
			}
			messages.add(userMessage(blocks));
			return exchange();
		}

		private LlmReply exchange() throws IOException {
			JsonObject body = new JsonObject();
			body.addProperty("model", BotSettings.AI_CLAUDE_MODEL.get());
			body.addProperty("max_tokens", MAX_TOKENS);
			body.addProperty("system", systemPrompt);
			body.add("messages", messages);
			body.add("tools", tools);
			body.addProperty("temperature", BotSettings.AI_TEMPERATURE.get());

			JsonObject reply = post(body);
			JsonArray content = reply.getAsJsonArray("content");
			if (content == null) {
				throw new IOException("Claude sent a reply with no content in it.");
			}

			// Echoed back verbatim next turn. Rebuilding it from the parsed pieces would drop any block
			// type this code does not know about, and the API rejects a tool_result whose tool_use is
			// missing from the history — so a block quietly lost here becomes an error two turns later.
			JsonObject assistant = new JsonObject();
			assistant.addProperty("role", "assistant");
			assistant.add("content", content);
			messages.add(assistant);

			return new LlmReply(text(content), parseCalls(content));
		}

		private JsonObject post(JsonObject body) throws IOException {
			HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
					.timeout(Duration.ofSeconds(BotSettings.AI_REQUEST_TIMEOUT.get()))
					.header("content-type", "application/json")
					.header("anthropic-version", API_VERSION)
					.header("x-api-key", apiKey())
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (ConnectException | UnknownHostException e) {
				throw new IOException("Can't reach the Anthropic API — check the internet connection. "
						+ "'/mcbot set aiProvider local' runs offline.");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Claude.");
			}

			if (response.statusCode() != 200) {
				throw new IOException(explain(response.statusCode(), response.body()));
			}
			try {
				return JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (JsonSyntaxException | IllegalStateException e) {
				throw new IOException("Claude sent something that wasn't JSON: " + brief(response.body()));
			}
		}
	}

	// ---------------------------------------------------------------- parsing

	/** Everything the model said in words, with the tool calls left out. */
	private static String text(JsonArray content) {
		StringBuilder text = new StringBuilder();
		for (JsonElement element : content) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject block = element.getAsJsonObject();
			if ("text".equals(typeOf(block)) && block.has("text")) {
				text.append(text.isEmpty() ? "" : " ").append(block.get("text").getAsString().trim());
			}
		}
		return text.toString().trim();
	}

	/**
	 * Pulls the calls out of the content blocks.
	 *
	 * <p>Argument values are flattened to strings whatever they arrived as, exactly as in
	 * {@link OllamaProvider} and for the same reason: the action layer parses text anyway, so
	 * normalising here keeps a model's inconsistency about quoting numbers from becoming a second
	 * thing to handle.</p>
	 */
	private static List<ToolCall> parseCalls(JsonArray content) {
		List<ToolCall> calls = new ArrayList<>();
		for (JsonElement element : content) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject block = element.getAsJsonObject();
			if (!"tool_use".equals(typeOf(block)) || !block.has("name")) {
				continue;
			}

			Map<String, String> arguments = new LinkedHashMap<>();
			JsonElement given = block.get("input");
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

			JsonElement id = block.get("id");
			calls.add(new ToolCall(
					id == null || id.isJsonNull() ? "" : id.getAsString(),
					block.get("name").getAsString(),
					arguments));
		}
		return calls;
	}

	private static String typeOf(JsonObject block) {
		JsonElement type = block.get("type");
		return type == null || type.isJsonNull() ? "" : type.getAsString();
	}

	// ---------------------------------------------------------------- request shaping

	private static JsonObject userMessage(JsonArray blocks) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", blocks);
		return message;
	}

	private static JsonArray textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		JsonArray blocks = new JsonArray();
		blocks.add(block);
		return blocks;
	}

	/**
	 * Renames the neutral schema's {@code parameters} to the {@code input_schema} Anthropic expects.
	 *
	 * <p>The whole adaptation, and the payoff for keeping
	 * {@link mcbot.client.api.ActionRegistry#schema()} vendor-neutral: the JSON Schema inside is
	 * byte-for-byte what Ollama gets, and only the key it hangs from differs. The copy is deliberate —
	 * the registry hands out the same array to whoever asks, so editing it in place would corrupt it
	 * for the next provider.</p>
	 */
	private static JsonArray wrapTools(JsonArray actionSchema) {
		JsonArray tools = new JsonArray();
		for (JsonElement action : actionSchema) {
			JsonObject source = action.getAsJsonObject();
			JsonObject tool = new JsonObject();
			tool.add("name", source.get("name"));
			tool.add("description", source.get("description"));
			tool.add("input_schema", source.get("parameters"));
			tools.add(tool);
		}
		return tools;
	}

	// ---------------------------------------------------------------- errors

	/**
	 * Turns an HTTP failure into something actionable.
	 *
	 * <p>Same reasoning as the Ollama version: every one of these has a one-line fix that "HTTP 401"
	 * does not hint at. The billing ones matter most here, because the mistake they follow from —
	 * assuming a Pro subscription covers the API — is one the error text alone does not correct.</p>
	 */
	private static String explain(int status, String body) {
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
		String model = BotSettings.AI_CLAUDE_MODEL.get();

		if (status == 401 || lower.contains("authentication_error")) {
			return "Anthropic rejected the API key. Check the " + KEY_NAMES.getFirst() + " in "
					+ ApiKeys.file() + " (or in the environment) holds a current key from "
					+ "console.anthropic.com — a Claude Pro login is a different thing and will not "
					+ "work here.";
		}
		if (status == 403 || lower.contains("permission_error")) {
			return "That key is not allowed to use " + model + ". Check the model name and what the "
					+ "key's workspace permits.";
		}
		if (status == 404 || lower.contains("not_found_error")) {
			return "Anthropic has no model called " + model + ". Check the id — "
					+ "'/mcbot set aiClaudeModel claude-haiku-4-5-20251001'.";
		}
		if (status == 400 && lower.contains("credit balance")) {
			return "The Anthropic account is out of credit. Top it up at console.anthropic.com — the "
					+ "API is billed separately from a Pro or Max subscription and is not included in "
					+ "one. '/mcbot set aiProvider local' works offline meanwhile.";
		}
		if (status == 429 || lower.contains("rate_limit_error")) {
			return "Rate-limited by Anthropic. Wait a moment and try again, or switch with "
					+ "'/mcbot set aiProvider local'.";
		}
		if (status == 529 || lower.contains("overloaded_error")) {
			return "Anthropic is overloaded right now. Try again shortly.";
		}
		if (status >= 500) {
			return "Anthropic returned a server error (HTTP " + status + "). Not your end; try again.";
		}
		return "Anthropic returned HTTP " + status + ": " + brief(body);
	}

	private static String brief(String body) {
		if (body == null || body.isBlank()) {
			return "(no detail)";
		}
		String trimmed = body.strip();
		return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
	}
}
