package descant.client.api.actions;

import java.util.List;

import descant.client.api.Action;
import descant.client.api.ActionContext;
import descant.client.api.ActionResult;
import descant.client.api.Arguments;
import descant.client.api.Parameter;
import descant.client.settings.BooleanSetting;
import descant.client.settings.Setting;
import descant.client.settings.SettingRegistry;

/**
 * Flips an on/off setting to whichever it currently is not.
 *
 * <p>Exists for the chat commands, where {@code /descant path} has always meant "the other one" and
 * having to know the current state first would be a step backwards. A model is better served by
 * {@code set}, where it says what it wants rather than what it wants changed — but the two share the
 * same settings, so neither can surprise the other.</p>
 */
public final class ToggleAction implements Action {

	@Override
	public String name() {
		return "toggle";
	}

	@Override
	public String description() {
		return "Flip an on/off setting to the opposite of what it is now. Prefer 'set' when you know "
				+ "which state you want — this is for when you only want it changed.";
	}

	@Override
	public List<Parameter> parameters() {
		return List.of(Parameter.choice("name", "Which on/off setting to flip.", true,
				SettingRegistry.all().stream()
						.filter(setting -> setting instanceof BooleanSetting)
						.map(Setting::name)
						.toList()));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		String wanted = arguments.getString("name");
		Setting setting = SettingRegistry.get(wanted);

		if (setting == null) {
			return ActionResult.failed("There is no setting called '" + wanted + "'.");
		}
		if (!(setting instanceof BooleanSetting flag)) {
			return ActionResult.failed(setting.name() + " is not an on/off setting — it takes "
					+ setting.domain() + ". Use 'set' instead.");
		}

		return ActionResult.ok(flag.name() + " is now " + (flag.toggle() ? "on" : "off") + ".");
	}
}
