package descant.client.api;

/**
 * The kinds of value an action parameter can take.
 *
 * <p>Deliberately few. Every argument arrives as text — from a chat command, or from a model that
 * writes JSON — and these four are what that text is checked against. A richer type system would
 * only push the same validation somewhere less visible.</p>
 */
public enum ParameterType {

	STRING("string"),
	INTEGER("integer"),
	NUMBER("number"),
	BOOLEAN("boolean");

	private final String jsonName;

	ParameterType(String jsonName) {
		this.jsonName = jsonName;
	}

	/** The name this type goes by in a JSON schema, which is how a model is told about it. */
	public String jsonName() {
		return jsonName;
	}
}
