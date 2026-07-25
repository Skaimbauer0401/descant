package mcbot.client.api;

import mcbot.client.control.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * What an action is given to work with.
 *
 * <p>Built fresh for every call rather than held onto, because {@link LocalPlayer} and the level are
 * replaced whenever the player changes world — a stored reference would go stale on the first trip
 * through a nether portal and keep answering questions about a world that no longer exists.</p>
 *
 * @param minecraft  the client
 * @param player     the player, guaranteed non-null and in a loaded world
 * @param controller the bot itself
 */
public record ActionContext(Minecraft minecraft, LocalPlayer player, BotController controller) {
}
