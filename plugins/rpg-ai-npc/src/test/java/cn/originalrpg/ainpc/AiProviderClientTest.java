package cn.originalrpg.ainpc;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiProviderClientTest {
    @Test
    void readsResponsesApiOutputBlocks() throws IOException {
        String json = """
                {"output":[{"type":"message","content":[
                  {"type":"output_text","text":"第一段"},
                  {"type":"output_text","text":"second"}
                ]}]}
                """;
        assertEquals("第一段\nsecond", AiProviderClient.parseResponsesResponse(json));
    }

    @Test
    void readsChatCompletionText() throws IOException {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer\"}}]}";
        assertEquals("answer", AiProviderClient.parseChatResponse(json));
    }

    @Test
    void rejectsNonJsonProviderBodies() {
        assertThrows(IOException.class, () -> AiProviderClient.parseResponsesResponse("gateway unavailable"));
    }

    @Test
    void refusesCredentialsOverPublicHttpButAllowsTlsAndLoopbackTunnels() {
        assertTrue(AiProviderClient.secureEndpoint("https://api.example.com/v1"));
        assertTrue(AiProviderClient.secureEndpoint("http://127.0.0.1:18080/v1"));
        assertTrue(AiProviderClient.secureEndpoint("http://[::1]:18080/v1"));
        assertTrue(AiProviderClient.secureEndpoint("http://localhost:18080/v1"));

        assertFalse(AiProviderClient.secureEndpoint("http://43.155.175.179:8080/v1"));
        assertFalse(AiProviderClient.secureEndpoint("http://127.0.0.1.example.com/v1"));
        assertFalse(AiProviderClient.secureEndpoint("https://user:secret@example.com/v1"));
        assertFalse(AiProviderClient.secureEndpoint("https://example.com/v1?token=secret"));
        assertFalse(AiProviderClient.secureEndpoint("//example.com/v1"));
        assertFalse(AiProviderClient.secureEndpoint("not a url"));
    }
}
