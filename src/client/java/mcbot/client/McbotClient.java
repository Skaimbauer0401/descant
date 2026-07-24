package mcbot.client;

import mcbot.client.command.McbotCommand;
import mcbot.client.control.BotController;
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

	/** The active controller, or {@code null} before mod initialisation has run. */
	public static BotController controller() {
		return controller;
	}

	@Override
	public void onInitializeClient() {
		controller = new BotController(McbotClient::sendChatMessage);

		PathRenderer pathRenderer = new PathRenderer(controller);
		pathRenderer.register();

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				new McbotCommand(controller, pathRenderer).register(dispatcher));

		// START_CLIENT_TICK runs before the player's input is polled, so a decision made here is
		// acted upon in the same tick rather than the next one.
		ClientTickEvents.START_CLIENT_TICK.register(minecraft -> {
			if (minecraft.player != null && minecraft.level != null) {
				controller.tick(minecraft);
			}
		});
	}

	private static void sendChatMessage(Component message) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(message);
		}
	}
}
