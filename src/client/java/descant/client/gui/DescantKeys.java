package descant.client.gui;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The key that opens {@link SettingsScreen}.
 *
 * <p>{@code G} by default, which vanilla leaves free. Bound rather than left unassigned because a
 * screen nobody can find is a screen nobody uses, and rebinding it takes one visit to the controls
 * menu whereas discovering an unbound key takes knowing it exists. {@code /descant config} opens the
 * same screen for anyone who has already given {@code G} to something else.</p>
 *
 * <p>Filed under Miscellaneous rather than in a category of its own: registering a category needs a
 * translation key to name it, and one keybind does not need a heading to itself.</p>
 */
public final class DescantKeys {

	private static final KeyMapping SETTINGS = new KeyMapping("key.descant.settings",
			InputConstants.Type.KEYSYM, InputConstants.KEY_G, KeyMapping.Category.MISC);

	private DescantKeys() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(SETTINGS);

		// END rather than START: a key pressed this tick has been polled by then, and opening a screen
		// halfway through the tick that is about to read the player's input is asking for the press to
		// be delivered to the game as well as to the screen.
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			// A loop, not an if: consumeClick reports each press once, and two in one tick are two.
			while (SETTINGS.consumeClick()) {
				open();
			}
		});
	}

	/**
	 * Opens the settings screen over whatever is showing.
	 *
	 * <p>Safe to call from anywhere on the client thread, including from a command — the screen it was
	 * typed into closes itself afterwards, so the caller there has to queue this for the next tick or
	 * watch it be closed again immediately.</p>
	 */
	public static void open() {
		Minecraft minecraft = Minecraft.getInstance();
		Screen showing = minecraft.gui.screen();
		if (showing instanceof SettingsScreen) {
			return;
		}
		minecraft.gui.setScreen(new SettingsScreen(showing));
	}
}
