package app.chat;

// A message from a chat: typed text, or a voice note (audio bytes and their content type,
// e.g. audio/ogg for a Telegram voice note).
public sealed interface ChatInput {

    record Text(String text) implements ChatInput {}

    record Voice(byte[] audio, String contentType) implements ChatInput {}
}
