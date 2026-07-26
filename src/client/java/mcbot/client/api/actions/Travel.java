package mcbot.client.api.actions;

import java.util.Arrays;
import java.util.List;

import mcbot.client.BotSettings;
import mcbot.client.api.Arguments;
import mcbot.client.api.Parameter;
import mcbot.client.control.TravelMode;
import mcbot.client.settings.SettingChoice;

/**
 * The shared {@code travel} argument, for every action that has to get somewhere first.
 *
 * <p>The companion to {@link Scaffold}: that one says <em>what</em> a journey may spend, this one
 * says <em>whether</em> it may spend anything at all. Both are single arguments repeated across the
 * travelling actions rather than a set of subtly different ones, so the same word means the same
 * thing wherever it is used.</p>
 *
 * <p>It replaced a {@code build} boolean on {@code goto} and {@code gotoLevel}. A boolean could only
 * offer the two extremes, and the useful answer is usually the third one — try it the harmless way,
 * dig only if that turns out not to work — which has no natural spelling as true or false.</p>
 */
final class Travel {

	/** Safe as a static field: building a {@link Parameter} touches no game state. */
	static final Parameter PARAMETER = Parameter.choice("travel", describe(), false,
			Arrays.stream(TravelMode.values()).map(SettingChoice::key).toList());

	private Travel() {
	}

	/** The mode the caller asked for, or the {@code travelMode} setting when they did not say. */
	static TravelMode mode(Arguments arguments) {
		return arguments.getChoice("travel", TravelMode.values(), BotSettings.TRAVEL_MODE.get());
	}

	/** A clause for the reply saying how the bot intends to get there. */
	static String note(TravelMode mode) {
		return switch (mode) {
			case WALK -> " Walking only — nothing will be mined or placed.";
			case BUILD -> " Mining and building a way through as needed." + Scaffold.note();
			case TRY_WALK -> " Walking there if it can; it will say so before it resorts to digging.";
		};
	}

	/**
	 * The argument's own description, assembled from the modes so the two cannot drift apart.
	 *
	 * <p>Each {@link TravelMode} already has to explain itself to {@code /mcbot set}. Writing the
	 * explanation a second time here would mean two copies to keep true.</p>
	 */
	private static String describe() {
		StringBuilder text = new StringBuilder("How the bot is allowed to get there. ");
		List<TravelMode> modes = Arrays.asList(TravelMode.values());
		for (TravelMode mode : modes) {
			text.append("'").append(mode.key()).append("' = ").append(mode.describe()).append(". ");
		}
		text.append("Defaults to the travelMode setting, normally '")
				.append(TravelMode.TRY_WALK.key())
				.append("'. Use 'walk' near anything the player has built, and 'build' only when "
						+ "digging is obviously part of the job.");
		return text.toString();
	}
}
