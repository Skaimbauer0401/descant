package mcbot.client.api.actions;

import java.util.List;
import java.util.stream.Collectors;

import mcbot.client.api.Action;
import mcbot.client.api.ActionContext;
import mcbot.client.api.ActionResult;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.api.ParameterType;
import mcbot.client.settings.Setting;
import mcbot.client.settings.SettingRegistry;

/**
 * Reads and changes any of the bot's settings by name.
 *
 * <p>One action rather than one per setting. The list is long and grows, and a menu with sixty
 * near-identical entries on it would crowd out the eight that actually do things — so the settings
 * are addressed by name through this single door, and the names themselves are offered as the
 * argument's allowed values so nothing has to guess at them.</p>
 *
 * <p>Three shapes, by how much you supply: nothing lists what has been changed, a name reads one
 * setting, and a name with a value writes it.</p>
 */
public final class SetAction implements Action {

	@Override
	public String name() {
		return "set";
	}

	@Override
	public String description() {
		return "Read or change one of the bot's settings. With no arguments it lists the settings that "
				+ "have been changed from their defaults; with a name it reports that setting's current "
				+ "value, what it does and what values it accepts; with a name and a value it changes it. "
				+ "Settings persist until changed back and affect everything the bot does afterwards.";
	}

	@Override
	public List<Parameter> parameters() {
		// Built from the registry rather than written out, so a setting added later is immediately
		// offered here instead of quietly being unreachable until someone remembers to list it.
		return List.of(
				Parameter.choice("name", "Which setting to read or change.", false,
						SettingRegistry.all().stream().map(Setting::name).toList()),
				Parameter.optional("value", ParameterType.STRING,
						"The new value. Omit to read the setting instead of changing it."));
	}

	@Override
	public ActionResult run(ActionContext context, Arguments arguments) {
		if (!arguments.has("name")) {
			return ActionResult.ok(listing());
		}

		String wanted = arguments.getString("name");
		Setting setting = SettingRegistry.get(wanted);
		if (setting == null) {
			return ActionResult.failed(suggest(wanted));
		}

		if (!arguments.has("value")) {
			return ActionResult.ok(describe(setting));
		}

		String previous = setting.asString();
		// Any complaint about the value comes back as IllegalArgumentException carrying a sentence
		// explaining what would have been accepted; the registry turns that into a failed result.
		setting.parse(arguments.getString("value"));
		return ActionResult.ok(setting.name() + ": " + previous + " → " + setting.asString() + ".");
	}

	private static String listing() {
		List<Setting> changed = SettingRegistry.modified();
		if (changed.isEmpty()) {
			return SettingRegistry.all().size() + " settings, all at their defaults. "
					+ "Give a name to read one, or a name and a value to change it.";
		}
		return changed.size() + " of " + SettingRegistry.all().size() + " settings changed: "
				+ changed.stream()
						.map(setting -> setting.name() + "=" + setting.asString())
						.collect(Collectors.joining(", "));
	}

	private static String describe(Setting setting) {
		return setting.name() + " = " + setting.asString()
				+ (setting.isDefault() ? " (default)" : " (default " + setting.defaultAsString() + ")")
				+ " — " + setting.description() + " Accepts " + setting.domain() + ".";
	}

	/**
	 * Turns a miss into something useful.
	 *
	 * <p>A near-miss is the common case — a remembered fragment, or a name in the wrong case — and
	 * answering it with the settings that contain that fragment saves a round trip, whether the
	 * reader is a person or a model correcting its own call.</p>
	 */
	private static String suggest(String wanted) {
		List<Setting> near = SettingRegistry.matching(wanted);
		if (near.isEmpty()) {
			return "There is no setting called '" + wanted + "'.";
		}
		return "There is no setting called '" + wanted + "'. Did you mean: "
				+ near.stream().map(Setting::name).collect(Collectors.joining(", ")) + "?";
	}
}
