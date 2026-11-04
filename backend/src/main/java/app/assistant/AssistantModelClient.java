package app.assistant;

import tools.jackson.databind.JsonNode;

import java.util.List;

// The language and speech models behind the assistant. Conversation items use the OpenAI
// Responses API format (user messages, function_call and function_call_output items), which
// AssistantService stores and sends back unchanged on the next request.
public interface AssistantModelClient {

    // Short name of the model in use, stored with each interaction.
    String modelName();

    // False when the server is missing what it needs, e.g. an API key.
    boolean isAvailable();

    // Speech in and out (transcribe and speak).
    boolean supportsVoice();

    Transcription transcribe(byte[] audio, String filename, List<String> keywords);

    // Spoken audio of the text, in the given format.
    byte[] speak(String text, SpeechFormat format);

    ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools);

    // seconds: the audio's length as the API reported it; 0 when it didn't say.
    record Transcription(String text, List<String> languages, double seconds) {
        public Transcription(String text, List<String> languages) {
            this(text, languages, 0);
        }
    }

    // outputItems are appended to the conversation as-is; toolCalls and text are read from them.
    record ModelResponse(List<JsonNode> outputItems, List<ToolCall> toolCalls, String text, Usage usage) {
        public ModelResponse(List<JsonNode> outputItems, List<ToolCall> toolCalls, String text) {
            this(outputItems, toolCalls, text, Usage.NONE);
        }
    }

    // Tokens a model call used, as the API reported them. inputTokens includes cachedTokens.
    record Usage(long inputTokens, long cachedTokens, long outputTokens) {
        public static final Usage NONE = new Usage(0, 0, 0);

        public Usage plus(Usage other) {
            return new Usage(inputTokens + other.inputTokens, cachedTokens + other.cachedTokens,
                    outputTokens + other.outputTokens);
        }
    }

    record ToolCall(String callId, String name, JsonNode arguments) {}

    // parameters is a JSON Schema in OpenAI strict mode: every property required, no extras.
    record ToolSpec(String name, String description, JsonNode parameters) {}
}
