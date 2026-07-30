package mcbot.client;

import mcbot.client.ai.AiAgent;
import mcbot.client.api.BotApi;
import mcbot.client.command.McbotCommand;
import mcbot.client.control.BotController;
import mcbot.client.gui.McbotKeys;
import mcbot.client.memory.Landmarks;
import mcbot.client.render.PathRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint.
 *
 * <p>Owns the single {@link BotController} instance and drives it once per client tick. The
 * controller is exposed statically because the input mixin has no other way to reach it — mixins
 * are woven into vanilla classes and cannot be given constructor dependencies.</p>
 */
public class McbotClient implements ClientModInitializer {

	private static BotController controller;
	private static BotApi api;
	private static AiAgent agent;

	/** The active controller, or {@code null} before mod initialisation has run. */
	public static BotController controller() {
		return controller;
	}

	/**
	 * The action API, or {@code null} before mod initialisation has run.
	 *
	 * <p>The single entry point for telling the bot to do anything — the chat commands go through it
	 * today, and a language model will go through the same door.</p>
	 */
	public static BotApi api() {
		return api;
	}

	/** The language-model driver, or {@code null} before mod initialisation has run. */
	public static AiAgent agent() {
		return agent;
	}

	@Override
	public void onInitializeClient() {
		controller = new BotController(McbotClient::sendChatMessage);
		api = new BotApi(controller);
		agent = new AiAgent(api, McbotClient::sendChatMessage);

		new PathRenderer(controller).register();
		McbotKeys.register();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				new McbotCommand(api, agent).register(dispatcher));

		// START_CLIENT_TICK runs before the player's input is polled, so a decision made here is
		// acted upon in the same tick rather than the next one.
		ClientTickEvents.START_CLIENT_TICK.register(minecraft -> {
			if (minecraft.player != null && minecraft.level != null) {
				controller.tick(minecraft);
				// Outside the controller on purpose: the bot should notice a stronghold it walks past
				// whether or not it is currently under orders, and memory is not a movement concern.
				Landmarks.tick(minecraft, minecraft.player);
			}
		});
	}

	/**
	 * Everything the controller and the agent say, on its way to chat.
	 *
	 * <p>Recorded as well as shown, so the next AI run can be told what just happened. The chat
	 * commands report through Brigadier's own feedback instead and record themselves — two callers,
	 * one {@link Transcript}.</p>
	 */
	private static void sendChatMessage(Component message) {
		Transcript.record(message.getString());
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(message);
		}
	}
}
