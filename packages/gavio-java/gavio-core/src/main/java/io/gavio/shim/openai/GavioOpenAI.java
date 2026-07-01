package io.gavio.shim.openai;

import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.types.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI drop-in shim (F-DX-04) — point existing OpenAI-style code at Gavio.
 *
 * <pre>{@code
 * GavioOpenAI client = new GavioOpenAI(gateway);
 * var resp = client.chat().completions().create(
 *     List.of(Message.of("user", "hi")), "gpt-4o");
 * System.out.println(resp.choices().get(0).message().content());
 * }</pre>
 */
public final class GavioOpenAI {

    private final Chat chat;

    public GavioOpenAI(Gateway gateway) {
        this.chat = new Chat(new Completions(gateway));
    }

    public Chat chat() {
        return chat;
    }

    public record Message2(String role, String content) {}

    public record Choice(int index, Message2 message, String finishReason) {}

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    /** OpenAI-shaped chat completion result. */
    public record ChatCompletion(String id, String model, List<Choice> choices, Usage usage) {}

    /** Namespace mirroring the OpenAI client's {@code client.chat}. */
    public static final class Chat {
        private final Completions completions;

        Chat(Completions completions) {
            this.completions = completions;
        }

        public Completions completions() {
            return completions;
        }
    }

    /** Namespace mirroring {@code client.chat.completions}. */
    public static final class Completions {
        private final Gateway gateway;

        Completions(Gateway gateway) {
            this.gateway = gateway;
        }

        public ChatCompletion create(List<Message> messages, String model) {
            return createAsync(messages, model).join();
        }

        public CompletableFuture<ChatCompletion> createAsync(List<Message> messages, String model) {
            GavioRequest request = GavioRequest.builder().messages(messages).model(model).build();
            return gateway.complete(request).thenApply(GavioOpenAI::toCompletion);
        }
    }

    private static ChatCompletion toCompletion(GavioResponse resp) {
        return new ChatCompletion(
                resp.traceId(),
                resp.modelVersion() != null && !resp.modelVersion().isEmpty()
                        ? resp.modelVersion()
                        : resp.model(),
                List.of(new Choice(0, new Message2("assistant", resp.content()), "stop")),
                new Usage(
                        resp.usage().promptTokens(),
                        resp.usage().completionTokens(),
                        resp.usage().totalTokens()));
    }
}
