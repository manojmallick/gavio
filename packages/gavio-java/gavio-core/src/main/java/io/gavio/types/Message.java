package io.gavio.types;

import java.util.Objects;

/** A provider-agnostic chat message. Immutable record. */
public record Message(String role, String content) {

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    /** Factory mirroring the Java SDK plan: {@code Message.of(role, content)}. */
    public static Message of(String role, String content) {
        return new Message(role, content);
    }

    /** Return a copy with replaced content. */
    public Message withContent(String newContent) {
        return new Message(role, newContent);
    }
}
