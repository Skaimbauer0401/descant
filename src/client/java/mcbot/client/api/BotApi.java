package mcbot.client.api;

import mcbot.client.BotSettings;
import mcbot.client.api.actions.ChestAction;
import mcbot.client.api.actions.CraftAction;
import mcbot.client.api.actions.DepositAction;
import mcbot.client.api.actions.DropAction;
import mcbot.client.api.actions.EquipAction;
import mcbot.client.api.actions.FindAction;
import mcbot.client.api.actions.GotoAction;
import mcbot.client.api.actions.GotoLevelAction;
import mcbot.client.api.actions.InventoryAction;
import mcbot.client.api.actions.LocateAction;
import mcbot.client.api.actions.LookAction;
import mcbot.client.api.actions.PlaceAction;
import mcbot.client.api.actions.SetAction;
import mcbot.client.api.actions.StatusAction;
import mcbot.client.api.actions.StopAction;
import mcbot.client.api.actions.ToggleAction;
import mcbot.client.api.actions.UseAction;
import mcbot.client.control.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The one way in.
 *
 * <p>Everything that can tell the bot to do something goes through here: the {@code /mcbot} chat
 * commands today, and a language model choosing from {@link ActionRegistry#schema()} next. Having a
 * single door is the point — two callers reaching into {@link BotController} separately would drift
 * apart, and the one that drifts is always the one nobody is watching.</p>
 *
 * <p><b>Client thread only.</b> Actions touch the player, the level and the controller, none of
 * which tolerate being read from elsewhere. Anything arriving on another thread — a model's reply
 * over HTTP, for instance — has to be handed to the client thread before it gets here.</p>
 */
public final class BotApi {

	private final BotController controller;
	private final ActionRegistry actions = new ActionRegistry();

	public BotApi(BotController controller) {
		this.controller = controller;

		// Force the settings to register before anything asks what they are — several actions offer
		// the setting names as argument values, and would otherwise offer an empty list.
		BotSettings.load();

		actions.register(new GotoAction());
		actions.register(new GotoLevelAction());
		actions.register(new FindAction());
		actions.register(new ChestAction());
		actions.register(new LocateAction());
		actions.register(new LookAction());
		actions.register(new CraftAction());
		actions.register(new UseAction());
		actions.register(new PlaceAction());
		actions.register(new InventoryAction());
		actions.register(new EquipAction());
		actions.register(new DropAction());
		actions.register(new DepositAction());
		actions.register(new StatusAction());
		actions.register(new StopAction());
		actions.register(new SetAction());
		actions.register(new ToggleAction());
	}

	/** The menu, for tab completion and for describing the bot to a model. */
	public ActionRegistry actions() {
		return actions;
	}

	/**
	 * Whether the bot is in the middle of something.
	 *
	 * <p>Used by the AI loop to tell a call that <em>started</em> work from one that finished on the
	 * spot, so the model can be told what actually happened rather than "heading there".</p>
	 */
	public boolean busy() {
		return controller.isActive();
	}

	/**
	 * Runs an action by name.
	 *
	 * <p>Never throws. Every outcome — a bad name, a bad argument, no world loaded — comes back as a
	 * {@link ActionResult} carrying a sentence saying so.</p>
	 */
	public ActionResult invoke(String name, Arguments arguments) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ActionResult.failed("Not in a world.");
		}
		return actions.invoke(new ActionContext(minecraft, player, controller), name, arguments);
	}
}
