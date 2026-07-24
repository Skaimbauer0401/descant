package mcbot.client.command;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import mcbot.client.control.BotController;
import mcbot.client.path.goal.Goal;
import mcbot.client.path.goal.GoalBlock;
import mcbot.client.path.goal.GoalXZ;
import mcbot.client.path.goal.GoalYLevel;
import mcbot.client.render.PathRenderer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The whole command surface, under a single {@code /mcbot} root.
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
 *   /mcbot stop | status | path | clutch
 * </pre>
 *
 * <p>Vanilla block, entity and coordinate argument types all resolve against a server-side command
 * source, which a client command does not have — hence plain integers and a string for the target.
 * Tab completion is supplied straight from the registries, so it behaves the same to the player.</p>
 *
 * <p>The target and the execute flag share one greedy argument and are split by hand. A greedy
 * string is needed because block ids contain a colon, which Brigadier's {@code word()} rejects, and
 * a greedy argument cannot be followed by another — so the trailing {@code true}/{@code false} is
 * peeled off the end instead.</p>
 */
public final class McbotCommand {

	private final BotController controller;
	private final PathRenderer pathRenderer;

	public McbotCommand(BotController controller, PathRenderer pathRenderer) {
		this.controller = controller;
		this.pathRenderer = pathRenderer;
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
				.then(ClientCommands.literal("stop").executes(this::stop))
				.then(ClientCommands.literal("status").executes(this::status))
				.then(ClientCommands.literal("path").executes(this::togglePath))
				.then(ClientCommands.literal("clutch").executes(this::toggleClutch)));
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
	private RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordinates(
			boolean allowBuilding) {
		return ClientCommands.<Integer>argument("first", IntegerArgumentType.integer())
				.executes(context -> travelToLevel(context, allowBuilding))
				.then(ClientCommands.<Integer>argument("second", IntegerArgumentType.integer())
						.executes(context -> travelToColumn(context, allowBuilding))
						.then(ClientCommands.<Integer>argument("third", IntegerArgumentType.integer())
								.executes(context -> travel(context, allowBuilding))));
	}

	/** Three numbers: an exact block. */
	private int travel(CommandContext<FabricClientCommandSource> context, boolean allowBuilding) {
		BlockPos goal = new BlockPos(
				IntegerArgumentType.getInteger(context, "first"),
				IntegerArgumentType.getInteger(context, "second"),
				IntegerArgumentType.getInteger(context, "third"));

		return start(context, new GoalBlock(goal), allowBuilding);
	}

	/**
	 * Two numbers: an X/Z column at whatever height the ground is.
	 *
	 * <p>The right form for travelling any real distance. Naming an exact Y a thousand blocks away
	 * means guessing the terrain height, and guessing wrong makes the bot tunnel down to your number
	 * or pillar up to it on arrival.</p>
	 */
	private int travelToColumn(CommandContext<FabricClientCommandSource> context,
			boolean allowBuilding) {
		LocalPlayer player = context.getSource().getClient().player;
		int referenceY = player == null ? 64 : (int) Math.floor(player.getY());

		return start(context, new GoalXZ(
				IntegerArgumentType.getInteger(context, "first"),
				IntegerArgumentType.getInteger(context, "second"),
				referenceY), allowBuilding);
	}

	/** One number: a height, reached anywhere — digging down to it, or climbing back up. */
	private int travelToLevel(CommandContext<FabricClientCommandSource> context,
			boolean allowBuilding) {
		LocalPlayer player = context.getSource().getClient().player;
		BlockPos column = player == null ? BlockPos.ZERO : BlockPos.containing(player.position());

		return start(context, new GoalYLevel(
				IntegerArgumentType.getInteger(context, "first"), column), allowBuilding);
	}

	private int start(CommandContext<FabricClientCommandSource> context, Goal goal,
			boolean allowBuilding) {
		controller.navigateTo(goal, allowBuilding, allowBuilding);
		feedback(context, "Heading to " + goal.describe()
				+ (allowBuilding ? "." : " (movement only).")
				+ " Press any movement key to cancel.");
		return 1;
	}

	// ---------------------------------------------------------------- find

	private int find(CommandContext<FabricClientCommandSource> context) {
		String raw = StringArgumentType.getString(context, "target").trim();

		// Peel an optional trailing true/false off the end. Defaults to false — plain `find` just
		// travels to the thing, and mining or killing it has to be asked for explicitly.
		boolean execute = false;
		int lastSpace = raw.lastIndexOf(' ');
		if (lastSpace > 0) {
			String tail = raw.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
			if (tail.equals("true") || tail.equals("false")) {
				execute = tail.equals("true");
				raw = raw.substring(0, lastSpace).trim();
			}
		}

		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null) {
			context.getSource().sendError(
					Component.literal("[mcbot] '" + raw + "' is not a valid name."));
			return 0;
		}

		Minecraft minecraft = context.getSource().getClient();
		LocalPlayer player = context.getSource().getPlayer();
		if (minecraft.level == null || player == null) {
			return 0;
		}

		// Blocks first, then mobs: the registries do not overlap and blocks are the common request.
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
		if (block.isPresent() && (block.get() != Blocks.AIR || raw.endsWith("air"))) {
			controller.huntFor(minecraft, player, block.get(),
					block.get().getName().getString(), execute);
			return 1;
		}

		Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
		if (type.isPresent()) {
			controller.huntForEntity(minecraft, player, type.get(),
					type.get().getDescription().getString(), execute);
			return 1;
		}

		context.getSource().sendError(
				Component.literal("[mcbot] No block or mob called '" + raw + "'."));
		return 0;
	}

	// ---------------------------------------------------------------- toggles and status

	private int stop(CommandContext<FabricClientCommandSource> context) {
		controller.stop(context.getSource().getClient());
		feedback(context, "Stopped.");
		return 1;
	}

	private int togglePath(CommandContext<FabricClientCommandSource> context) {
		feedback(context, "Path display " + (pathRenderer.toggle() ? "shown." : "hidden."));
		return 1;
	}

	private int toggleClutch(CommandContext<FabricClientCommandSource> context) {
		feedback(context, "Water-bucket clutch " + (controller.toggleClutch()
				? "enabled — long drops are now survivable." : "disabled."));
		return 1;
	}

	private int status(CommandContext<FabricClientCommandSource> context) {
		StringBuilder text = new StringBuilder(controller.status().toString());
		BlockPos goal = controller.goal();
		if (goal != null) {
			text.append(" → ").append(goal.getX()).append(", ")
					.append(goal.getY()).append(", ").append(goal.getZ());
		}
		switch (controller.status()) {
			case PLANNING -> text.append(" (searched ").append(controller.searchedNodes())
					.append(" nodes)");
			case FOLLOWING, BREAKING, PLACING, MINING -> text.append(" (")
					.append(controller.remainingSteps()).append(" steps left)");
			default -> {
			}
		}
		feedback(context, text.toString());
		return 1;
	}

	private static void feedback(CommandContext<FabricClientCommandSource> context, String message) {
		context.getSource().sendFeedback(Component.literal("[mcbot] " + message));
	}
}
