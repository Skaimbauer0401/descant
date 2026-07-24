package mcbot.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mcbot.client.McbotClient;
import mcbot.client.control.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;

/**
 * Hands the bot control of the movement keys.
 *
 * <p>{@code KeyboardInput.tick()} reads the physical keyboard into {@link ClientInput#keyPresses}.
 * Injecting at the tail means we see what the human pressed — used to cancel the bot the moment
 * they take over — and then substitute the bot's own keys before anything downstream consumes
 * them.</p>
 *
 * <p>Overwriting this field rather than moving the player directly is deliberate. It is the exact
 * value that vanilla feeds to the movement physics and packs into the player-input packet, so the
 * bot's movement is simulated and validated identically to a human's. There is no teleporting and
 * nothing for the server to reject.</p>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void mcbot$applyBotInput(CallbackInfo info) {
		BotController controller = McbotClient.controller();
		if (controller == null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();

		// Inspect the real keyboard state first: any manual movement releases the bot.
		controller.onManualInput(minecraft, this.keyPresses);

		if (controller.isActive()) {
			// Both fields must be written. keyPresses feeds jumping, sneaking and the outgoing
			// input packet; moveVector is the cached derivative that the walking physics actually
			// reads. Setting only the first produces a player that jumps on the spot.
			this.keyPresses = controller.input().toVanilla();
			this.moveVector = controller.input().toMoveVector();
		}
	}
}
