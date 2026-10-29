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

    // Spoken audio (MP3) of the text.
    byte[] speak(String text);

    ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools);

    record Transcription(String text, List<String> languages) {}

    // outputItems are appended to the conversation as-is; toolCalls and text are read from them.
    record ModelResponse(List<JsonNode> outputItems, List<ToolCall> toolCalls, String text) {}

    record ToolCall(String callId, String name, JsonNode arguments) {}

    // parameters is a JSON Schema in OpenAI strict mode: every property required, no extras.
    record ToolSpec(String name, String description, JsonNode parameters) {}
}
