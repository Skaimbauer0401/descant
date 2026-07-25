package mcbot.client.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import mcbot.client.BotSettings;
import mcbot.client.api.Action;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.BotApi;
import mcbot.client.inventory.ChestSource;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/**
 * The {@code /mcbot} chat commands.
 *
 * <pre>
 *   /mcbot goto &lt;x&gt; &lt;y&gt; &lt;z&gt;        travel to that block, mining and bridging as needed
 *   /mcbot goto &lt;x&gt; &lt;z&gt;            travel to that column, at whatever height the ground is
 *   /mcbot goto &lt;y&gt;                reach that height, anywhere
 *   /mcbot walk  &lt;same forms&gt;      as above, but without modifying the world
 *   /mcbot find &lt;block|mob&gt; [true|false]  go to the nearest block or mob. Add `true` to mine
 *                                          or kill it, sweep up the drops and move on to the
 *                                          next; the default is false, which simply travels
 *                                          there once
 *   /mcbot chest [looking|nearest|off]    pick the container to bank the haul in
 *   /mcbot set [&lt;name&gt;] [&lt;value&gt;]  list, read or change a setting
 *   /mcbot api                     write the action menu out as JSON
 *   /mcbot stop | status | path
 * </pre>
 *
 * <p>This class only parses. Every command ends in a call to {@link BotApi}, and the sentence the
 * player sees is the one the action gave back — so the chat commands and a model driving the bot go
 * down the same path and cannot come to disagree about what {@code find} means.</p>
 *
 * <p>The Brigadier tree is still written by hand rather than generated from the action menu, because
 * the two want different shapes. A model is best served by named arguments; at a keyboard it is
 * quicker to type three numbers and let their meaning follow from how many there were.</p>
 *
 * <p>Vanilla block, entity and coordinate argument types all resolve against a server-side command
 * source, which a client command does not have — hence plain integers and a string for the target.
 * Tab completion is supplied straight from the registries, so it behaves the same to the player.</p>
 */
public final class McbotCommand {

	private final BotApi api;

	public McbotCommand(BotApi api) {
		this.api = api;
	}

	public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("mcbot")
				.then(ClientCommands.literal("goto").then(coordinates(true)))
				.then(ClientCommands.literal("walk").then(coordinates(false)))
				.then(ClientCommands.literal("find")
						.then(ClientCommands.<String>argument("target", StringArgumentType.greedyString())
								.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
										Stream.concat(
												BuiltInRegistries.BLOCK.keySet().stream(),
												BuiltInRegistries.ENTITY_TYPE.keySet().stream()),
										builder))
								.executes(this::find)))
				.then(chest())
				.then(set())
				.then(ClientCommands.literal("api").executes(this::dumpApi))
				.then(ClientCommands.literal("stop")
						.executes(context -> run(context, "stop", Arguments.none())))
				.then(ClientCommands.literal("status")
						.executes(context -> run(context, "status", Arguments.none())))
				.then(ClientCommands.literal("path")
						.executes(context -> run(context, "toggle",
								Arguments.of("name", BotSettings.SHOW_PATH.name())))));
	}

	// ---------------------------------------------------------------- travel

	/**
	 * The coordinate forms, following Baritone's {@code goto}: how many numbers you give decides what
	 * kind of goal you mean.
	 *
	 * <p>The arguments are named positionally rather than {@code x}/{@code y}/{@code z} because the
	 * second number means different things depending on whether a third follows — Brigadier has one
	 * node per position, so the interpretation has to happen in the handler.</p>
	 */
	private RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordinates(boolean build) {
		return ClientCommands.<Integer>argument("first", IntegerArgumentType.integer())
				.executes(context -> run(context, "gotoLevel", Arguments.of(
						"y", IntegerArgumentType.getInteger(context, "first"),
						"build", build)))
				.then(ClientCommands.<Integer>argument("second", IntegerArgumentType.integer())
						.executes(context -> run(context, "goto", Arguments.of(
								"x", IntegerArgumentType.getInteger(context, "first"),
								"z", IntegerArgumentType.getInteger(context, "second"),
								"build", build)))
						.then(ClientCommands.<Integer>argument("third", IntegerArgumentType.integer())
								.executes(context -> run(context, "goto", Arguments.of(
										"x", IntegerArgumentType.getInteger(context, "first"),
										"y", IntegerArgumentType.getInteger(context, "second"),
										"z", IntegerArgumentType.getInteger(context, "third"),
										"build", build)))));
	}

	private int find(CommandContext<FabricClientCommandSource> context) {
		String raw = StringArgumentType.getString(context, "target").trim();

		// Peel an optional trailing true/false off the end. A greedy string is needed because block
		// ids contain a colon, which Brigadier's word() rejects, and a greedy argument cannot be
		// followed by another — so the flag is split off by hand rather than parsed as its own node.
		String execute = null;
		int lastSpace = raw.lastIndexOf(' ');
		if (lastSpace > 0) {
			String tail = raw.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
			if (tail.equals("true") || tail.equals("false")) {
				execute = tail;
				raw = raw.substring(0, lastSpace).trim();
			}
		}
		return run(context, "find", Arguments.of("target", raw, "execute", execute));
	}

	// ---------------------------------------------------------------- banking

	/**
	 * {@code /mcbot chest [looking|nearest|off]}.
	 *
	 * <p>The sources are spelled out as literals rather than left to a free string so that they tab
	 * complete, which for most people is the only documentation of them they will ever read. Bare
	 * {@code /mcbot chest} falls back to the {@code chestSource} setting.</p>
	 */
	private LiteralArgumentBuilder<FabricClientCommandSource> chest() {
		LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal("chest")
				.executes(context -> run(context, "chest", Arguments.none()))
				.then(ClientCommands.literal("off")
						.executes(context -> run(context, "chest", Arguments.of("source", "off"))));

		for (ChestSource source : ChestSource.values()) {
			node = node.then(ClientCommands.literal(source.key())
					.executes(context -> run(context, "chest", Arguments.of("source", source.key()))));
		}
		return node;
	}

	// ---------------------------------------------------------------- settings

	/** {@code /mcbot set [<name>] [<value>]} — list what has changed, read one, or change one. */
	private LiteralArgumentBuilder<FabricClientCommandSource> set() {
		return ClientCommands.literal("set")
				.executes(context -> run(context, "set", Arguments.none()))
				.then(ClientCommands.<String>argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								SettingRegistry.all().stream().map(Setting::name), builder))
						.executes(context -> run(context, "set", Arguments.of(
								"name", StringArgumentType.getString(context, "name"))))
						.then(ClientCommands.<String>argument("value", StringArgumentType.greedyString())
								.suggests(McbotCommand::suggestValues)
								.executes(context -> run(context, "set", Arguments.of(
										"name", StringArgumentType.getString(context, "name"),
										"value", StringArgumentType.getString(context, "value"))))));
	}

	/**
	 * Offers a setting's accepted values.
	 *
	 * <p>For an on/off or multiple-choice setting those are the whole answer. For a number there is
	 * nothing to enumerate, so the current value is offered instead — which is the useful thing to
	 * start from when nudging one.</p>
	 */
	private static CompletableFuture<Suggestions> suggestValues(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		Setting setting = SettingRegistry.get(StringArgumentType.getString(context, "name"));
		if (setting == null) {
			return builder.buildFuture();
		}
		Stream<String> options = switch (setting.type()) {
			case "boolean" -> Stream.of("true", "false");
			// domain() reads "one of: looking (…), nearest (…)"; the key is the word before the gloss.
			case "choice" -> Stream.of(setting.domain().replace("one of: ", "").split(", "))
					.map(option -> option.split(" ")[0]);
			default -> Stream.of(setting.asString());
		};
		return SharedSuggestionProvider.suggest(options, builder);
	}

	// ---------------------------------------------------------------- the action menu

	/**
	 * Lists the actions in chat and writes the full menu out as JSON.
	 *
	 * <p>That JSON is what a language model gets handed to work out what the bot can do. Having it on
	 * disk means it can be read, diffed and pasted into a prompt without the game running — which is
	 * how you notice an action's description is too vague to act on before a model does.</p>
	 */
	private int dumpApi(CommandContext<FabricClientCommandSource> context) {
		for (Action action : api.actions().all()) {
			String arguments = action.parameters().stream()
					.map(parameter -> parameter.required()
							? " <" + parameter.name() + ">"
							: " [" + parameter.name() + "]")
					.reduce("", String::concat);
			feedback(context, action.name() + arguments);
		}

		Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("mcbot-actions.json");
		try {
			Files.writeString(file,
					new GsonBuilder().setPrettyPrinting().create().toJson(api.actions().schema()),
					StandardCharsets.UTF_8);
			feedback(context, api.actions().all().size() + " actions, written to " + file + ".");
			return 1;
		} catch (IOException e) {
			context.getSource().sendError(Component.literal(
					"[mcbot] Couldn't write the action menu: " + e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- plumbing

	/**
	 * Runs an action and reports what it said.
	 *
	 * <p>A {@code quiet} result is one the bot has already announced through its own chat messages;
	 * printing it again would say the same thing twice, one line after the other.</p>
	 */
	private int run(CommandContext<FabricClientCommandSource> context, String action,
			Arguments arguments) {
		ActionResult result = api.invoke(action, arguments);
		if (!result.quiet()) {
			if (result.ok()) {
				feedback(context, result.message());
			} else {
				context.getSource().sendError(Component.literal("[mcbot] " + result.message()));
			}
		}
		return result.ok() ? 1 : 0;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendFeedback(Component.literal("[mcbot] " + message));
	}
}
