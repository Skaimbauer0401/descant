package descant.client.action;

/** Outcome of one tick of a world-editing action. */
public enum ActionState {

	/** Still in progress — call again next tick. */
	WORKING,

	/** The action completed successfully. */
	DONE,

	/** The target is too far away; the controller should walk closer and retry. */
	OUT_OF_RANGE,

	/**
	 * The player genuinely has no suitable item for this action.
	 *
	 * <p>Distinct from {@link #FAILED} so the bot can report the real reason. Reporting "out of
	 * building blocks" for every placement failure — a bad anchor, a timeout — sent us hunting for
	 * an inventory problem that did not exist.</p>
	 */
	NO_MATERIAL,

	/**
	 * There is somewhere for the items to go, but no space left in it.
	 *
	 * <p>Distinct from {@link #FAILED} because a full chest is not a broken chest. Treating the two
	 * alike made the bot forget a perfectly good chest it had simply filled up, and the difference
	 * decides whether the answer is "carry on without it" or "there is nowhere left to put anything".</p>
	 */
	NO_ROOM,

	/** The action cannot be completed at all (unbreakable, no anchor, timed out). */
	FAILED
}
