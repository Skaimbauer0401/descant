package descant.client.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import com.mojang.brigadier.tree.LiteralCommandNode;

import descant.client.BotSettings;
import descant.client.ai.AiAgent;
import descant.client.api.Action;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.BotApi;
import descant.client.control.TravelMode;
import descant.client.gui.DescantKeys;
import descant.client.inventory.ChestSource;
import descant.client.memory.Place;
import descant.client.memory.Places;
import descant.client.Transcript;
import descant.client.settings.Setting;
import descant.client.settings.SettingRegistry;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The {@code /descant} chat commands.
 *
 * <pre>
 *   /descant goto &lt;x&gt; &lt;y&gt; &lt;z&gt;        travel to that block, walking if it can and digging if it must
 *   /descant goto &lt;x&gt; &lt;z&gt;            travel to that column, at whatever height the ground is
 *   /descant goto &lt;y&gt;                reach that height, anywhere
 *   /descant walk  &lt;same forms&gt;      as above, but without modifying the world
 *   /descant dig   &lt;same forms&gt;      as above, mining and bridging from the start
 *   /descant find &lt;block|mob&gt; [true|false] [count]
 *                                  go to the nearest block or mob. Add `true` to mine or kill
 *                                  it, sweep up the drops and move on to the next; the default
 *                                  is false, which simply travels there once. Add a number to
 *                                  stop after that many — a count on its own implies `true`
 *   /descant chest [looking|nearest|off]    pick the container to bank the haul in
 *   /descant place &lt;block&gt; [&lt;x&gt; &lt;y&gt; &lt;z&gt;]  put a block down, in front or at a spot
 *   /descant mine &lt;x&gt; &lt;y&gt; &lt;z&gt;        break the block at that exact spot
 *   /descant locate &lt;block&gt; [count]  report where the nearest ones are, without moving
 *   /descant remember &lt;name&gt;   write this spot down under a name
 *   /descant recall [&lt;query&gt;]  list what the bot remembers, nearest first
 *   /descant forget &lt;name&gt;     drop one; 'noticed' drops everything it found by itself
 *   /descant look                    describe the surroundings: where, biome, time, what is nearby
 *   /descant craft &lt;item&gt; [count]    make something, at a bench if the recipe needs one
 *   /descant smelt &lt;item&gt; [count]    run a furnace: find it, load it, wait, collect
 *   /descant use                     right-click whatever the bot is looking at
 *   /descant inventory               list what is carried
 *   /descant deposit [haul|food|all|&lt;item&gt;]  stash it now; defaults to the haul
 *   /descant take &lt;food|all|&lt;item&gt;&gt; [count]  fetch it back out of the chest
 *   /descant equip &lt;item&gt; [auto|hand|offhand]  hold it, wear it, or off-hand it
 *   /descant armour                  put on the best armour carried
 *   /descant drop &lt;item&gt; [count]
 *   /descant ai &lt;what you want&gt;      hand the job to a language model
 *   /descant ai stop                 call it off
 *   /descant set [&lt;name&gt;] [&lt;value&gt;]  list, read or change a setting
 *   /descant config                  open the settings screen (also on the G key)
 *   /descant api                     write the action menu out as JSON
 *   /descant stop | status | path
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
public final class DescantCommand {

	private final BotApi api;
	private final AiAgent agent;

	public DescantCommand(BotApi api, AiAgent agent) {
		this.api = api;
		this.agent = agent;
	}

	/**
	 * Builds the command tree under {@code /descant}, and points {@code /mcbot} at it.
	 *
	 * <p>The alias is not politeness. This mod answered to {@code mcbot} for its whole life so far,
	 * and every note, macro and habit built around it says that word — a rename that makes all of them
	 * report "unknown command" teaches nothing except that the mod broke. Brigadier redirects cost one
	 * node: the old name reaches the same tree, is never suggested by tab-completion, and can be
	 * dropped once nothing types it.</p>
	 */
	public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
				ClientCommands.literal("descant")
				.then(ClientCommands.literal("goto").then(coordinates(TravelMode.TRY_WALK)))
				.then(ClientCommands.literal("walk").then(coordinates(TravelMode.WALK)))
				.then(ClientCommands.literal("dig").then(coordinates(TravelMode.BUILD)))
				.then(ClientCommands.literal("find")
						.then(ClientCommands.<String>argument("target", StringArgumentType.greedyString())
								.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
										Stream.concat(
												BuiltInRegistries.BLOCK.keySet().stream(),
												BuiltInRegistries.ENTITY_TYPE.keySet().stream()),
										builder))
								.executes(this::find)))
				.then(chest())
				.then(place())
				.then(ClientCommands.literal("mine")
						.then(ClientCommands.<Integer>argument("x", IntegerArgumentType.integer())
								.then(ClientCommands.<Integer>argument("y", IntegerArgumentType.integer())
										.then(ClientCommands.<Integer>argument("z", IntegerArgumentType.integer())
												.executes(context -> run(context, "mine", Arguments.of(
														"x", IntegerArgumentType.getInteger(context, "x"),
														"y", IntegerArgumentType.getInteger(context, "y"),
														"z", IntegerArgumentType.getInteger(context, "z"))))))))
				.then(equip())
				.then(ClientCommands.literal("armour")
						.executes(context -> run(context, "armour", Arguments.none())))
				.then(drop())
				.then(ClientCommands.literal("look")
						.executes(context -> run(context, "look", Arguments.none())))
				.then(ClientCommands.literal("use")
						.executes(context -> run(context, "use", Arguments.none())))
				.then(ClientCommands.literal("craft")
						.then(ClientCommands.<String>argument("item", StringArgumentType.word())
								.suggests(DescantCommand::suggestItems)
								.executes(context -> run(context, "craft", Arguments.of(
										"item", StringArgumentType.getString(context, "item"))))
								.then(ClientCommands.<Integer>argument("count", IntegerArgumentType.integer(1))
										.executes(context -> run(context, "craft", Arguments.of(
												"item", StringArgumentType.getString(context, "item"),
												"count", IntegerArgumentType.getInteger(context, "count")))))))
				.then(ClientCommands.literal("smelt")
						.then(ClientCommands.<String>argument("item", StringArgumentType.word())
								.suggests(DescantCommand::suggestItems)
								.executes(context -> run(context, "smelt", Arguments.of(
										"item", StringArgumentType.getString(context, "item"))))
								.then(ClientCommands.<Integer>argument("count", IntegerArgumentType.integer(1))
										.executes(context -> run(context, "smelt", Arguments.of(
												"item", StringArgumentType.getString(context, "item"),
												"count", IntegerArgumentType.getInteger(context, "count")))))))
				.then(ClientCommands.literal("locate")
						.then(ClientCommands.<String>argument("block", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::getPath), builder))
								.executes(context -> run(context, "locate", Arguments.of(
										"block", StringArgumentType.getString(context, "block"))))
								.then(ClientCommands.<Integer>argument("count", IntegerArgumentType.integer(1))
										.executes(context -> run(context, "locate", Arguments.of(
												"block", StringArgumentType.getString(context, "block"),
												"count", IntegerArgumentType.getInteger(context, "count")))))))
				.then(ClientCommands.literal("inventory")
						.executes(context -> run(context, "inventory", Arguments.none())))
				.then(ClientCommands.literal("take")
						.then(ClientCommands.<String>argument("what", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										Stream.concat(Stream.of("food", "all"),
												BuiltInRegistries.ITEM.keySet().stream().map(Identifier::getPath)),
										builder))
								.executes(context -> run(context, "take", Arguments.of(
										"what", StringArgumentType.getString(context, "what"))))
								.then(ClientCommands.<Integer>argument("count", IntegerArgumentType.integer(1))
										.executes(context -> run(context, "take", Arguments.of(
												"what", StringArgumentType.getString(context, "what"),
												"count", IntegerArgumentType.getInteger(context, "count")))))))
				.then(ClientCommands.literal("deposit")
						.executes(context -> run(context, "deposit", Arguments.none()))
						.then(ClientCommands.<String>argument("what", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										Stream.concat(Stream.of("haul", "food", "all"),
												BuiltInRegistries.ITEM.keySet().stream().map(Identifier::getPath)),
										builder))
								.executes(context -> run(context, "deposit", Arguments.of(
										"what", StringArgumentType.getString(context, "what"))))))
				.then(memory())
				.then(ClientCommands.literal("recall")
						.executes(context -> run(context, "recall", Arguments.none()))
						.then(ClientCommands.<String>argument("query", StringArgumentType.greedyString())
								.suggests(DescantCommand::suggestPlaces)
								.executes(context -> run(context, "recall", Arguments.of(
										"query", StringArgumentType.getString(context, "query"))))))
				.then(ClientCommands.literal("forget")
						.then(ClientCommands.<String>argument("name", StringArgumentType.greedyString())
								.suggests(DescantCommand::suggestPlaces)
								.executes(context -> run(context, "forget", Arguments.of(
										"name", StringArgumentType.getString(context, "name"))))))
				.then(set())
				.then(ClientCommands.literal("config").executes(DescantCommand::openSettings))
				.then(ai())
				.then(ClientCommands.literal("api").executes(this::dumpApi))
				.then(ClientCommands.literal("stop")
						.executes(context -> run(context, "stop", Arguments.none())))
				.then(ClientCommands.literal("status")
						.executes(context -> run(context, "status", Arguments.none())))
				.then(ClientCommands.literal("path")
						.executes(context -> run(context, "toggle",
								Arguments.of("name", BotSettings.SHOW_PATH.name())))));

		dispatcher.register(ClientCommands.literal("mcbot").redirect(root));
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
	private RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordinates(TravelMode mode) {
		return ClientCommands.<Integer>argument("first", IntegerArgumentType.integer())
				.executes(context -> run(context, "gotoLevel", Arguments.of(
						"y", IntegerArgumentType.getInteger(context, "first"),
						"travel", mode.key())))
				.then(ClientCommands.<Integer>argument("second", IntegerArgumentType.integer())
						.executes(context -> run(context, "goto", Arguments.of(
								"x", IntegerArgumentType.getInteger(context, "first"),
								"z", IntegerArgumentType.getInteger(context, "second"),
								"travel", mode.key())))
						.then(ClientCommands.<Integer>argument("third", IntegerArgumentType.integer())
								.executes(context -> run(context, "goto", Arguments.of(
										"x", IntegerArgumentType.getInteger(context, "first"),
										"y", IntegerArgumentType.getInteger(context, "second"),
										"z", IntegerArgumentType.getInteger(context, "third"),
										"travel", mode.key())))));
	}

	/** {@code /descant find <block|mob> [true|false] [count]}. */
	private int find(CommandContext<FabricClientCommandSource> context) {
		String raw = StringArgumentType.getString(context, "target").trim();

		// The optional trailing arguments are peeled off the end by hand. A greedy string is needed
		// because block ids contain a colon, which Brigadier's word() rejects, and nothing may follow
		// a greedy argument — so they cannot be nodes of their own.
		//
		// Peeled right to left, count before the flag, since that is the order they are written in.
		String count = null;
		String tail = lastWord(raw);
		if (tail != null && tail.chars().allMatch(Character::isDigit)) {
			count = tail;
			raw = withoutLastWord(raw);
		}
		String execute = null;
		tail = lastWord(raw);
		if (tail != null && (tail.equals("true") || tail.equals("false"))) {
			execute = tail;
			raw = withoutLastWord(raw);
		}
		// A count on its own means business: nobody asks for twenty of something they only want to
		// walk to, and making them type 'true' as well would be a papercut with no upside.
		if (count != null && execute == null) {
			execute = "true";
		}
		return run(context, "find",
				Arguments.of("target", raw, "execute", execute, "count", count));
	}

	/** The last space-separated word, lowercased, or {@code null} when there is only one. */
	private static String lastWord(String text) {
		int lastSpace = text.lastIndexOf(' ');
		return lastSpace > 0 ? text.substring(lastSpace + 1).toLowerCase(Locale.ROOT) : null;
	}

	private static String withoutLastWord(String text) {
		return text.substring(0, text.lastIndexOf(' ')).trim();
	}

	// ---------------------------------------------------------------- banking

	/**
	 * {@code /descant chest [looking|nearest|off]}.
	 *
	 * <p>The sources are spelled out as literals rather than left to a free string so that they tab
	 * complete, which for most people is the only documentation of them they will ever read. Bare
	 * {@code /descant chest} falls back to the {@code chestSource} setting.</p>
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

	// ---------------------------------------------------------------- the model

	/**
	 * {@code /descant ai <what you want>} — hand the job to a language model.
	 *
	 * <p>Deliberately <em>not</em> an action. Everything else the bot can do is on the menu the model
	 * chooses from, and putting "ask a model" on that menu would let it call itself.</p>
	 */
	private LiteralArgumentBuilder<FabricClientCommandSource> ai() {
		return ClientCommands.literal("ai")
				.executes(context -> report(context, agent.isRunning()
						? ActionResult.ok("Working on it. /descant ai stop to call it off.")
						: ActionResult.failed("Say what you want, e.g. /descant ai get me some iron.")))
				.then(ClientCommands.literal("stop").executes(context -> report(context, agent.stop())))
				.then(ClientCommands.<String>argument("goal", StringArgumentType.greedyString())
						.executes(context -> report(context,
								agent.start(StringArgumentType.getString(context, "goal")))));
	}

	// ---------------------------------------------------------------- inventory and building

	/** {@code /descant place <block> [<x> <y> <z>]}. */
	private LiteralArgumentBuilder<FabricClientCommandSource> place() {
		return ClientCommands.literal("place")
				.then(ClientCommands.<String>argument("block", StringArgumentType.word())
						.suggests(DescantCommand::suggestItems)
						.executes(context -> run(context, "place", Arguments.of(
								"block", StringArgumentType.getString(context, "block"))))
						.then(ClientCommands.<Integer>argument("x", IntegerArgumentType.integer())
								.then(ClientCommands.<Integer>argument("y", IntegerArgumentType.integer())
										.then(ClientCommands.<Integer>argument("z", IntegerArgumentType.integer())
												.executes(context -> run(context, "place", Arguments.of(
														"block", StringArgumentType.getString(context, "block"),
														"x", IntegerArgumentType.getInteger(context, "x"),
														"y", IntegerArgumentType.getInteger(context, "y"),
														"z", IntegerArgumentType.getInteger(context, "z"))))))));
	}

	/** {@code /descant equip <item> [auto|hand|offhand]}. */
	private LiteralArgumentBuilder<FabricClientCommandSource> equip() {
		return ClientCommands.literal("equip")
				.then(ClientCommands.<String>argument("item", StringArgumentType.word())
						.suggests(DescantCommand::suggestItems)
						.executes(context -> run(context, "equip", Arguments.of(
								"item", StringArgumentType.getString(context, "item"))))
						.then(ClientCommands.<String>argument("where", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										Stream.of("auto", "hand", "offhand"), builder))
								.executes(context -> run(context, "equip", Arguments.of(
										"item", StringArgumentType.getString(context, "item"),
										"where", StringArgumentType.getString(context, "where"))))));
	}

	/** {@code /descant drop <item> [count]} — without a count, all of them. */
	private LiteralArgumentBuilder<FabricClientCommandSource> drop() {
		return ClientCommands.literal("drop")
				.then(ClientCommands.<String>argument("item", StringArgumentType.word())
						.suggests(DescantCommand::suggestItems)
						.executes(context -> run(context, "drop", Arguments.of(
								"item", StringArgumentType.getString(context, "item"))))
						.then(ClientCommands.<Integer>argument("count", IntegerArgumentType.integer(1))
								.executes(context -> run(context, "drop", Arguments.of(
										"item", StringArgumentType.getString(context, "item"),
										"count", IntegerArgumentType.getInteger(context, "count"))))));
	}

	/**
	 * Offers item ids <em>without</em> the {@code minecraft:} namespace.
	 *
	 * <p>Brigadier's {@code word()} rejects a colon, and the alternative — a greedy string — cannot be
	 * followed by the coordinates {@code place} wants. Since the namespace is optional everywhere it
	 * is read, leaving it out keeps every suggestion something that actually parses.</p>
	 */
	private static CompletableFuture<Suggestions> suggestItems(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
				BuiltInRegistries.ITEM.keySet().stream().map(Identifier::getPath), builder);
	}

	// ---------------------------------------------------------------- settings

	/** {@code /descant set [<name>] [<value>]} — list what has changed, read one, or change one. */
	private LiteralArgumentBuilder<FabricClientCommandSource> set() {
		return ClientCommands.literal("set")
				.executes(context -> run(context, "set", Arguments.none()))
				.then(ClientCommands.<String>argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								SettingRegistry.all().stream().map(Setting::name), builder))
						.executes(context -> run(context, "set", Arguments.of(
								"name", StringArgumentType.getString(context, "name"))))
						.then(ClientCommands.<String>argument("value", StringArgumentType.greedyString())
								.suggests(DescantCommand::suggestValues)
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
		List<String> options = setting.options();
		return SharedSuggestionProvider.suggest(
				options.isEmpty() ? Stream.of(setting.asString()) : options.stream(), builder);
	}

	// ---------------------------------------------------------------- the screen

	/**
	 * {@code /descant config} — the same settings, with somewhere to click.
	 *
	 * <p>Queued for the next tick rather than opened here. This runs while the chat screen it was typed
	 * into is still up, and that screen closes itself once the command returns — taking anything opened
	 * during it with it.</p>
	 */
	private static int openSettings(CommandContext<FabricClientCommandSource> context) {
		Minecraft.getInstance().execute(DescantKeys::open);
		feedback(context, "Opening the settings. The G key opens them too.");
		return 1;
	}

	// ---------------------------------------------------------------- memory

	/** {@code remember}, {@code recall} and {@code forget}, which share one suggestion source. */
	private LiteralArgumentBuilder<FabricClientCommandSource> memory() {
		return ClientCommands.literal("remember")
				.then(ClientCommands.<String>argument("name", StringArgumentType.greedyString())
						.executes(context -> run(context, "remember", Arguments.of(
								"name", StringArgumentType.getString(context, "name")))));
	}

	/** Offers the names the bot has written down for this world. */
	private static CompletableFuture<Suggestions> suggestPlaces(
			CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(
				Places.here(Minecraft.getInstance()).stream().map(Place::name), builder);
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

		Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("descant-actions.json");
		try {
			Files.writeString(file,
					new GsonBuilder().setPrettyPrinting().create().toJson(api.actions().schema()),
					StandardCharsets.UTF_8);
			feedback(context, api.actions().all().size() + " actions, written to " + file + ".");
			return 1;
		} catch (IOException e) {
			context.getSource().sendError(Component.literal(
					"[descant] Couldn't write the action menu: " + e.getMessage()));
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
		return report(context, api.invoke(action, arguments));
	}

	/** Prints a result the way the player expects, whoever produced it. */
	private static int report(CommandContext<FabricClientCommandSource> context, ActionResult result) {
		if (!result.quiet()) {
			if (result.ok()) {
				feedback(context, result.message());
			} else {
				context.getSource().sendError(Component.literal("[descant] " + result.message()));
			}
		}
		return result.ok() ? 1 : 0;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> context, String message) {
		// Recorded as well as shown: a locate or inventory run by hand is exactly the context the next
		// '/descant ai ...' is likely to be referring to.
		Transcript.record("[descant] " + message);
		context.getSource().sendFeedback(Component.literal("[descant] " + message));
	}
}
