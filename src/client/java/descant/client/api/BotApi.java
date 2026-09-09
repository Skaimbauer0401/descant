package descant.client.api;

import descant.client.BotSettings;
import descant.client.api.actions.ChestAction;
import descant.client.api.actions.CraftAction;
import descant.client.api.actions.ArmourAction;
import descant.client.api.actions.DepositAction;
import descant.client.api.actions.DropAction;
import descant.client.api.actions.EquipAction;
import descant.client.api.actions.FindAction;
import descant.client.api.actions.ForgetAction;
import descant.client.api.actions.GotoAction;
import descant.client.api.actions.GotoLevelAction;
import descant.client.api.actions.InventoryAction;
import descant.client.api.actions.LocateAction;
import descant.client.api.actions.LookAction;
import descant.client.api.actions.MineAction;
import descant.client.api.actions.PlaceAction;
import descant.client.api.actions.RecallAction;
import descant.client.api.actions.RememberAction;
import descant.client.api.actions.SetAction;
import descant.client.api.actions.SmeltAction;
import descant.client.api.actions.StatusAction;
import descant.client.api.actions.TakeAction;
import descant.client.api.actions.StopAction;
import descant.client.api.actions.ToggleAction;
import descant.client.api.actions.UseAction;
import descant.client.control.BotController;
import descant.client.settings.SettingRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * The one way in.
 *
 * <p>Everything that can tell the bot to do something goes through here: the {@code /descant} chat
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
		// Then whatever was changed last time. This order is required, not stylistic: settings
		// register themselves as they are constructed, so applying saved values first would be
		// applying them to an empty registry.
		SettingRegistry.load();

		actions.register(new GotoAction());
		actions.register(new GotoLevelAction());
		actions.register(new FindAction());
		actions.register(new ChestAction());
		actions.register(new LocateAction());
		actions.register(new LookAction());
		actions.register(new CraftAction());
		actions.register(new SmeltAction());
		actions.register(new UseAction());
		actions.register(new PlaceAction());
		actions.register(new MineAction());
		actions.register(new InventoryAction());
		actions.register(new EquipAction());
		actions.register(new ArmourAction());
		actions.register(new DropAction());
		actions.register(new DepositAction());
		actions.register(new TakeAction());
		actions.register(new RememberAction());
		actions.register(new RecallAction());
		actions.register(new ForgetAction());
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
