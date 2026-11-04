package app.chat;

// What to send back to a chat. heard is what a voice note was transcribed as (null for text), so
// the owner can see it was understood. When proposalId is set, the chat shows Confirm and Cancel
// buttons that call ChatMessageService.confirm and cancel with it.
public record ChatReply(String heard, String text, Long proposalId) {

    static ChatReply text(String text) {
        return new ChatReply(null, text, null);
    }
}
