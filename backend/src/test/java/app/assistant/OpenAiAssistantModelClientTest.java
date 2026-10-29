package app.assistant;

import app.assistant.AssistantModelClient.ModelResponse;
import app.assistant.AssistantModelClient.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

// Checks the exact requests sent to OpenAI and how responses are read, without a real API key.
class OpenAiAssistantModelClientTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.test/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiAssistantModelClient client =
            new OpenAiAssistantModelClient(builder.build(), true, "gpt-6-luna", "gpt-transcribe", jsonMapper);

    @Test
    void respondSendsAStatelessStrictToolRequestAndReadsToolCallsAndText() {
        server.expect(requestTo("https://api.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.instructions").value("Be helpful"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.include[0]").value("reasoning.encrypted_content"))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andExpect(jsonPath("$.input[0].content").value("今天 chicken rice 42"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].name").value("list_dishes"))
                .andExpect(jsonPath("$.tools[0].strict").value(true))
                .andExpect(jsonPath("$.tools[0].parameters.additionalProperties").value(false))
                .andRespond(withSuccess("""
                        {"id": "resp_1", "output": [
                          {"type": "reasoning", "id": "rs_1", "encrypted_content": "abc", "summary": []},
                          {"type": "function_call", "id": "fc_1", "call_id": "call_1", "name": "list_dishes", "arguments": "{}"},
                          {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "Checking your dishes."}]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        ModelResponse response = client.respond("Be helpful",
                List.of(jsonMapper.readTree("{\"role\": \"user\", \"content\": \"今天 chicken rice 42\"}")),
                List.of(new ToolSpec("list_dishes", "List dishes",
                        jsonMapper.readTree("{\"type\": \"object\", \"properties\": {}, \"required\": [], \"additionalProperties\": false}"))));

        server.verify();
        // Every output item, including the encrypted reasoning, is kept to send back next time.
        assertEquals(3, response.outputItems().size());
        assertEquals("reasoning", response.outputItems().getFirst().path("type").asString());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call_1", response.toolCalls().getFirst().callId());
        assertEquals("list_dishes", response.toolCalls().getFirst().name());
        assertTrue(response.toolCalls().getFirst().arguments().isObject());
        assertEquals("Checking your dishes.", response.text());
    }

    @Test
    void transcribeSendsDishNamesAsKeywordsAndEnglishAndChineseHints() {
        server.expect(requestTo("https://api.test/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", containsString("multipart/form-data")))
                .andExpect(content().string(containsString("gpt-transcribe")))
                .andExpect(content().string(containsString("name=\"keywords[]\"")))
                .andExpect(content().string(containsString("Chicken rice")))
                .andExpect(content().string(containsString("name=\"languages[]\"")))
                .andExpect(content().string(containsString("filename=\"speech.webm\"")))
                .andRespond(withSuccess("""
                        {"text": "今天 chicken rice 42", "languages": [{"code": "zh"}, {"code": "en"}]}
                        """, MediaType.APPLICATION_JSON));

        var transcription = client.transcribe(new byte[]{1, 2, 3}, "speech.webm", List.of("Chicken rice", "bad<word>", "鸡饭"));

        server.verify();
        assertEquals("今天 chicken rice 42", transcription.text());
        assertEquals(List.of("zh", "en"), transcription.languages());
    }

    @Test
    void anErrorFromOpenAiBecomesABadGateway() {
        server.expect(requestTo("https://api.test/v1/responses"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        var error = assertThrows(AssistantException.class, () -> client.respond("x", List.<JsonNode>of(), List.of()));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        assertEquals("ASSISTANT_UPSTREAM_ERROR", error.getCode());
    }

    @Test
    void anOpenAiRateLimitTellsTheUserToTryLater() {
        server.expect(requestTo("https://api.test/v1/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"code\": \"rate_limit_exceeded\", \"message\": \"Rate limit reached\"}}"));

        var error = assertThrows(AssistantException.class, () -> client.respond("x", List.<JsonNode>of(), List.of()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals("ASSISTANT_BUSY", error.getCode());
    }

    @Test
    void withoutAKeyTheClientIsUnavailableAndSendsNothing() {
        var unconfigured = new OpenAiAssistantModelClient(builder.build(), false, "gpt-6-luna", "gpt-transcribe", jsonMapper);

        assertFalse(unconfigured.isAvailable());
        var error = assertThrows(AssistantException.class, () -> unconfigured.respond("x", List.of(), List.of()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        server.verify();
    }
}
