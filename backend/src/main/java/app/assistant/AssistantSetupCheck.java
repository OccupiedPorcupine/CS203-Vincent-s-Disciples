package app.assistant;

import app.diagnostics.SetupCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

// Asks OpenAI about the chat model, which costs nothing and shows both whether the key works and
// whether the model in app.assistant.chat-model exists.
@Component
public class AssistantSetupCheck implements SetupCheck {

    private final String provider;
    private final String apiKey;
    private final String chatModel;
    private final RestClient restClient;

    public AssistantSetupCheck(@Value("${app.assistant.provider}") String provider,
                               @Value("${app.assistant.openai.api-key}") String apiKey,
                               @Value("${app.assistant.openai.base-url}") String baseUrl,
                               @Value("${app.assistant.chat-model}") String chatModel) {
        this.provider = provider;
        this.apiKey = apiKey.strip();
        this.chatModel = chatModel;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public String name() {
        return "assistant";
    }

    @Override
    public Result check() {
        if (!"openai".equals(provider)) {
            return Result.off("ASSISTANT_PROVIDER is " + provider + ": a rule-based stand-in answers typed sales, with no voice.");
        }
        if (apiKey.isEmpty()) {
            return Result.problem("ASSISTANT_PROVIDER is openai but OPENAI_API_KEY is not set, so the assistant can't answer.");
        }
        try {
            restClient.get().uri("/models/{model}", chatModel).header("Authorization", "Bearer " + apiKey)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            return switch (exception.getStatusCode().value()) {
                case 401 -> Result.problem("OpenAI refused OPENAI_API_KEY.");
                case 404 -> Result.problem("OpenAI has no model called " + chatModel + " (app.assistant.chat-model).");
                default -> Result.problem("OpenAI answered " + exception.getStatusCode().value() + ".");
            };
        } catch (RestClientException exception) {
            return Result.problem("Could not reach OpenAI: " + exception.getMessage());
        }
        return Result.ok("OpenAI accepts the key and has the chat model " + chatModel + ".");
    }
}
