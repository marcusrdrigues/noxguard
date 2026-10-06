package com.marcusrdrigues.noxguard.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.marcusrdrigues.noxguard.agent.ToolPolicy;
import com.marcusrdrigues.noxguard.example.web.ChatResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The whole app in its default profile: scripted model, no API key. */
@SpringBootTest
class ChatControllerTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    @DisplayName("POST /api/chat/complete answers with the passages and a signature")
    void complete() {
        ChatResponse response = client.post().uri("/api/chat/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", "Where is the store?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatResponse.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(response);
        assertEquals("We're at 12 Sample Street. More at https://example.com/visit.", response.answer());
        assertNotNull(response.sig());
        assertTrue(response.context().stream().anyMatch(c -> c.contains("12 Sample Street")));
    }

    @Test
    @DisplayName("POST /api/chat streams JSON lines ending with done")
    void stream() {
        List<Map> lines = client.post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(Map.of("question", "When is the store open?"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Map.class)
                .getResponseBody()
                .collectList()
                .block();
        assertNotNull(lines);
        assertTrue(lines.size() > 2, "the answer came in pieces");
        assertEquals("done", lines.getLast().get("t"));
    }

    @Test
    @DisplayName("an empty question is a 400")
    void emptyQuestion() {
        client.post().uri("/api/chat/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", " "))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private ChatResponse complete(String question) {
        return client.post().uri("/api/chat/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", question))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatResponse.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    @DisplayName("the tool policy comes from application.yml through the starter")
    void toolPolicyFromProperties() {
        ToolPolicy policy = context.getBean(ToolPolicy.class);
        assertEquals(List.of("check_stock", "propose_message"), policy.toolNames());
        assertTrue(policy.requiresConfirmation("propose_message"));

        assertEquals("Dune: In stock: 3 copies.", complete("Is \"Dune\" in stock?").answer());
        assertEquals("I couldn't check that title. Ask with the book's name, like \"Dune\".", complete("Is \"../../etc/passwd\" in stock?").answer());
        ChatResponse cancel = complete("Cancel my order 1042.");
        assertEquals(List.of(new ChatResponse.ToolCall("cancel_order")), cancel.toolCalls());
        assertTrue(cancel.answer().contains("hello@example.com"));
    }
}
