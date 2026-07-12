package io.gavio.types;

/** Supported LLM providers. String-valued for easy config and logging. */
public enum Provider {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    AZURE_OPENAI("azure_openai"),
    OPENROUTER("openrouter"),
    OLLAMA("ollama"),
    BEDROCK("bedrock"),
    COHERE("cohere"),
    MOCK("mock");

    private final String value;

    Provider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Accept either an enum member or its string value. */
    public static Provider coerce(Object value) {
        if (value instanceof Provider p) {
            return p;
        }
        String s = String.valueOf(value).toLowerCase();
        for (Provider p : values()) {
            if (p.value.equals(s)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown provider: " + value);
    }
}
