package mcbot.client.control;

import mcbot.client.settings.SettingChoice;

/**
 * How much of the world a journey is allowed to rearrange to get where it is going.
 *
 * <p>Every trip used to be {@link #BUILD}, including the ones nobody asked to be a trip at all —
 * placing a furnace forty blocks away is a request to place a furnace, and the walk over is a means
 * to it. Getting a tunnel through the garden wall as part of the bargain is a surprise.</p>
 *
 * <p>{@link #TRY_WALK} exists because the choice is usually not either-or. Walking is almost always
 * possible and always preferable; when it genuinely is not — the target is inside a hill, or across
 * a ravine — refusing to dig would just mean the job never happens. So the bot tries the cheap way
 * and escalates, which is what a person would do, and says so when it does.</p>
 */
public enum TravelMode implements SettingChoice {

	/**
	 * Never break or place. The journey fails if there is no walkable route.
	 *
	 * <p>The one to use around anything that took someone effort to build.</p>
	 */
	WALK("walk", "walk only, leaving the world untouched; fails if there is no route on foot"),

	/** Mine and build freely from the start. The old behaviour, and right when digging is the point. */
	BUILD("build", "mine and build a way through from the start"),

	/**
	 * Walk if it can, dig and bridge if it must.
	 *
	 * <p>The upgrade is triggered by the journey <em>failing</em>, not by predicting it will. A
	 * walk-only search that cannot reach the goal usually still returns a partial route and the bot
	 * covers real ground on it, so deciding up front would give up ground it could have walked.</p>
	 */
	TRY_WALK("try_walk", "try walking first, and only dig or bridge if there turns out to be no way on foot");

	private final String key;
	private final String description;

	TravelMode(String key, String description) {
		this.key = key;
		this.description = description;
	}

	@Override
	public String key() {
		return key;
	}

	@Override
	public String describe() {
		return description;
	}

	/** Whether a journey in this mode starts out allowed to break and place. */
	public boolean buildsImmediately() {
		return this == BUILD;
	}
}
