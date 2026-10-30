package app.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// Calls OpenAI's Responses API (language model with function calling) and transcription API.
// Nothing is stored at OpenAI (store=false); the encrypted reasoning items it returns are kept
// in the conversation and sent back, as the API requires for reasoning models.
public class OpenAiAssistantModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAssistantModelClient.class);

    // OpenAI rejects keywords containing these characters.
    private static final String KEYWORD_FORBIDDEN = "[<>\\r\\n]";
    private static final int MAX_KEYWORDS = 100;
    private static final List<String> LANGUAGE_HINTS = List.of("en", "zh");

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final boolean configured;
    private final String chatModel;
    private final String transcribeModel;

    public OpenAiAssistantModelClient(String baseUrl, String apiKey, String chatModel, String transcribeModel, JsonMapper jsonMapper) {
        this(buildRestClient(baseUrl, apiKey), apiKey != null && !apiKey.isBlank(), chatModel, transcribeModel, jsonMapper);
    }

    OpenAiAssistantModelClient(RestClient restClient, boolean configured, String chatModel, String transcribeModel, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.configured = configured;
        this.chatModel = chatModel;
        this.transcribeModel = transcribeModel;
        this.jsonMapper = jsonMapper;
    }

    private static RestClient buildRestClient(String baseUrl, String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public String modelName() {
        return chatModel;
    }

    @Override
    public boolean isAvailable() {
        return configured;
    }

    @Override
    public boolean supportsVoice() {
        return configured;
    }

    @Override
    public Transcription transcribe(byte[] audio, String filename, List<String> keywords) {
        requireConfigured();

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model", transcribeModel);
        form.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        keywords.stream()
                .map(keyword -> keyword.replaceAll(KEYWORD_FORBIDDEN, " ").strip())
                .filter(keyword -> !keyword.isEmpty())
                .distinct()
                .limit(MAX_KEYWORDS)
                .forEach(keyword -> form.add("keywords[]", keyword));
        LANGUAGE_HINTS.forEach(language -> form.add("languages[]", language));

        JsonNode body = jsonMapper.readTree(call(() -> restClient.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class)));

        // OpenAI's language tags change between runs for the same clip, so read them from the text.
        String text = body.path("text").asString();
        List<String> languages = languagesIn(text);
        if (languages.isEmpty()) {
            body.path("languages").forEach(language -> languages.add(language.path("code").asString()));
        }
        return new Transcription(text, languages);
    }

    // In order of first appearance: Chinese characters are "zh", Latin letters "en".
    static List<String> languagesIn(String text) {
        List<String> languages = new ArrayList<>();
        text.codePoints().forEach(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            String language = script == Character.UnicodeScript.HAN ? "zh"
                    : script == Character.UnicodeScript.LATIN ? "en" : null;
            if (language != null && !languages.contains(language)) {
                languages.add(language);
            }
        });
        return languages;
    }

    @Override
    public ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools) {
        requireConfigured();
        String responseBody = call(() -> restClient.post()
                .uri("/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(buildRequest(instructions, input, tools)))
                .retrieve()
                .body(String.class));
        return parseResponse(jsonMapper.readTree(responseBody));
    }

    ObjectNode buildRequest(String instructions, List<JsonNode> input, List<ToolSpec> tools) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", chatModel);
        request.put("instructions", instructions);
        request.put("store", false);
        request.putArray("include").add("reasoning.encrypted_content");

        ArrayNode inputArray = request.putArray("input");
        input.forEach(inputArray::add);

        ArrayNode toolArray = request.putArray("tools");
        for (ToolSpec tool : tools) {
            ObjectNode spec = toolArray.addObject();
            spec.put("type", "function");
            spec.put("name", tool.name());
            spec.put("description", tool.description());
            spec.set("parameters", tool.parameters());
            spec.put("strict", true);
        }
        return request;
    }

    ModelResponse parseResponse(JsonNode response) {
        List<JsonNode> outputItems = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        for (JsonNode item : response.path("output")) {
            outputItems.add(item);
            switch (item.path("type").asString()) {
                case "function_call" -> toolCalls.add(new ToolCall(
                        item.path("call_id").asString(),
                        item.path("name").asString(),
                        jsonMapper.readTree(item.path("arguments").asString("{}"))));
                case "message" -> item.path("content").forEach(content -> {
                    if ("output_text".equals(content.path("type").asString())) {
                        text.append(content.path("text").asString());
                    }
                });
                default -> {
                    // Reasoning items are kept for the next request but not read.
                }
            }
        }
        return new ModelResponse(outputItems, toolCalls, text.toString());
    }

    private void requireConfigured() {
        if (!configured) {
            throw AssistantException.unavailable("The assistant is not set up: OPENAI_API_KEY is missing on the server.");
        }
    }

    private String call(java.util.function.Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            // OpenAI's error body says why (e.g. which limit was hit); it never contains the key.
            log.warn("OpenAI returned {}: {}", exception.getStatusCode().value(), exception.getResponseBodyAsString());
            int status = exception.getStatusCode().value();
            if (status == 429) {
                // Rate limit or used-up quota on the OpenAI account.
                throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_BUSY",
                        "The assistant has reached its usage limit for now. Please try again later.");
            }
            if (status == 401 || status == 403) {
                throw AssistantException.unavailable("The assistant's OpenAI key was rejected. Check OPENAI_API_KEY on the server.");
            }
            throw AssistantException.upstream("The AI service returned an error (" + status + "). Please try again.");
        } catch (RestClientException exception) {
            throw AssistantException.upstream("The AI service could not be reached. Please try again.");
        }
    }
}
