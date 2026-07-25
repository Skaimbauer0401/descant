package mcbot.client.ai;

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

import mcbot.client.BotSettings;

/**
 * Talks to Ollama.
 *
 * <p>One class covers both the local models and Ollama's cloud ones, because a cloud model is served
 * <em>through the same local daemon</em> — {@code ollama signin}, then a model name ending in
 * {@code -cloud}, and the daemon proxies it. The only difference that reaches this code is the
 * string in {@code model}, so there is nothing here to switch on.</p>
 *
 * <p>Everything blocks. Called from the agent's worker thread, never the client thread.</p>
 */
public final class OllamaProvider implements LlmProvider {

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	@Override
	public String describe() {
		return (BotSettings.AI_CLOUD.get() ? "ollama cloud " : "ollama local ") + model();
	}

	/** Whichever of the two models is currently selected. */
	private static String model() {
		return BotSettings.AI_CLOUD.get()
				? BotSettings.AI_CLOUD_MODEL.get()
				: BotSettings.AI_MODEL.get();
	}

	@Override
	public Session begin(String systemPrompt, JsonArray tools) {
		return new OllamaSession(systemPrompt, tools);
	}

	private final class OllamaSession implements Session {

		/** The running conversation, in Ollama's own shape — kept verbatim so nothing is lost. */
		private final JsonArray messages = new JsonArray();
		private final JsonArray tools;

		OllamaSession(String systemPrompt, JsonArray actionSchema) {
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
				// Naming the tool lets the model line the answer up with the call it made, which
				// matters as soon as it issues two in one turn.
				result.addProperty("tool_name", outcome.call().name());
				messages.add(result);
			}
			return exchange();
		}

		private LlmReply exchange() throws IOException {
			JsonObject body = new JsonObject();
			body.addProperty("model", model());
			body.add("messages", messages);
			body.add("tools", tools);
			body.addProperty("stream", false);

			JsonObject options = new JsonObject();
			// Low temperature. Choosing the right function from a list is not a task that benefits
			// from invention, and a small model at default heat will cheerfully make up an action.
			options.addProperty("temperature", BotSettings.AI_TEMPERATURE.get());
			body.add("options", options);

			JsonObject reply = post("/api/chat", body);
			JsonObject assistant = reply.getAsJsonObject("message");
			if (assistant == null) {
				throw new IOException("Ollama sent a reply with no message in it.");
			}
			// Echoed back verbatim on the next turn, tool calls and all, so the model can see what it
			// already decided. Rebuilding it from parsed pieces would quietly drop whatever we had not
			// thought to parse.
			messages.add(assistant);

			return new LlmReply(text(assistant), parseCalls(assistant));
		}

		private JsonObject post(String path, JsonObject body) throws IOException {
			String url = BotSettings.AI_HOST.get().replaceAll("/+$", "") + path;
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(BotSettings.AI_REQUEST_TIMEOUT.get()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response;
			try {
				response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (ConnectException e) {
				throw new IOException("Can't reach Ollama at " + BotSettings.AI_HOST.get()
						+ ". Is it running? Start it with 'ollama serve'.");
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
				throw new IOException("Ollama sent something that wasn't JSON: " + brief(response.body()));
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
	 * <p>Argument values are flattened to strings whatever they arrived as. Models are inconsistent
	 * about whether a coordinate is a number or a quoted number, and sometimes inconsistent within one
	 * reply; the action layer parses text anyway, so normalising here means that variation never
	 * becomes a second thing to handle.</p>
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
			JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
			if (function == null || !function.has("name")) {
				continue;
			}

			Map<String, String> arguments = new LinkedHashMap<>();
			JsonElement given = function.get("arguments");
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

			JsonElement id = element.getAsJsonObject().get("id");
			calls.add(new ToolCall(
					id == null || id.isJsonNull() ? "" : id.getAsString(),
					function.get("name").getAsString(),
					arguments));
		}
		return calls;
	}

	// ---------------------------------------------------------------- request shaping

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	/**
	 * Wraps the neutral action schema in the envelope Ollama expects.
	 *
	 * <p>This is the whole reason {@link mcbot.client.api.ActionRegistry#schema()} stays vendor-neutral:
	 * the object inside is identical across providers, and only the wrapper differs.</p>
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
	 * <p>Worth the trouble because every one of these is a first-run problem with a one-line fix, and
	 * "HTTP 404" does not hint at any of them.</p>
	 */
	private static String explain(int status, String body) {
		String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);

		if (lower.contains("does not support tools") || lower.contains("not support tool")) {
			return model() + " can't call tools, so it can't drive the bot. Try a tool-capable model "
					+ "such as qwen3:14b or mistral-nemo:12b.";
		}
		if (lower.contains("llama-server") || lower.contains("binary not found")) {
			return "Your Ollama install is missing its local runner (llama-server), so no local model "
					+ "can start. Reinstall Ollama from ollama.com — or use a cloud model instead with "
					+ "'/mcbot set aiCloud true', which is proxied and does not need it.";
		}
		if (status == 410 || lower.contains("was retired")) {
			return model() + " has been retired by Ollama. Pick a current one — "
					+ "'/mcbot set aiCloudModel minimax-m3:cloud' — and see ollama.com/search?c=cloud.";
		}
		if (lower.contains("requires a subscription") || lower.contains("upgrade for access")) {
			return model() + " needs a paid Ollama subscription. The free cloud models that can drive "
					+ "the bot include minimax-m3:cloud, nemotron-3-ultra:cloud and gemma4:cloud.";
		}
		if (status == 404 || lower.contains("not found") || lower.contains("try pulling")) {
			return model() + " isn't installed. Pull it with 'ollama pull " + model() + "'"
					+ (model().endsWith("-cloud") || model().endsWith(":cloud")
							? " — cloud models also need 'ollama signin' first."
							: ".");
		}
		if (status == 401 || status == 403) {
			return "Ollama refused the request for " + model()
					+ ". Cloud models need 'ollama signin' first.";
		}
		if (status == 402 || lower.contains("quota") || lower.contains("rate limit")) {
			return "Out of cloud quota for " + model()
					+ ". Wait for it to reset, or switch back with '/mcbot set aiCloud false'.";
		}
		return "Ollama returned HTTP " + status + ": " + brief(body);
	}

	private static String brief(String body) {
		if (body == null || body.isBlank()) {
			return "(no detail)";
		}
		String trimmed = body.strip();
		return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
	}
}
