package io.gavio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gavio.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writeAndParseRoundTrip() {
        Map<String, Object> obj = Map.of(
                "model", "gpt-4o",
                "max_tokens", 100,
                "messages", List.of(Map.of("role", "user", "content", "hi \"there\"\n")));
        String json = Json.write(obj);
        Map<String, Object> parsed = Json.parseObject(json);
        assertEquals("gpt-4o", parsed.get("model"));
        assertEquals(100L, parsed.get("max_tokens"));
        @SuppressWarnings("unchecked")
        List<Object> msgs = (List<Object>) parsed.get("messages");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) msgs.get(0);
        assertEquals("hi \"there\"\n", first.get("content"));
    }

    @Test
    void escapesSpecialCharacters() {
        String json = Json.write(Map.of("k", "a\tb\\c"));
        assertTrue(json.contains("\\t"));
        assertTrue(json.contains("\\\\"));
    }

    @Test
    void parsesNestedArraysAndNulls() {
        Object v = Json.parse("{\"a\":[1,2.5,true,null],\"b\":{}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        @SuppressWarnings("unchecked")
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(2.5, a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(null, a.get(3));
    }
}
