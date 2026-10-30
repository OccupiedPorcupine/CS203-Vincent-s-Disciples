package app.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AssistantConfig {

    // "Today" for the assistant means today in Singapore, wherever the server runs.
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Singapore"));
    }

    @Bean
    public AssistantModelClient assistantModelClient(
            @Value("${app.assistant.provider}") String provider,
            @Value("${app.assistant.openai.api-key}") String apiKey,
            @Value("${app.assistant.openai.base-url}") String baseUrl,
            @Value("${app.assistant.chat-model}") String chatModel,
            @Value("${app.assistant.transcribe-model}") String transcribeModel,
            JsonMapper jsonMapper
    ) {
        return switch (provider) {
            case "openai" -> new OpenAiAssistantModelClient(baseUrl, apiKey, chatModel, transcribeModel, jsonMapper);
            case "offline" -> new OfflineAssistantModelClient(jsonMapper);
            default -> throw new IllegalStateException("app.assistant.provider must be openai or offline, not " + provider);
        };
    }
}
