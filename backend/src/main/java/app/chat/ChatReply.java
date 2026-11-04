package app.chat;

// What to send back to a chat. heard is what a voice note was transcribed as (null for text), so
// the owner can see it was understood. card is a proposal as text (ChatProposalText); when it is
// set, so is proposalId, and the chat shows Confirm and Cancel buttons that call
// ChatMessageService.confirm and cancel with it. A voice-note reply speaks only reply.
public record ChatReply(String heard, String reply, String card, Long proposalId) {

    static ChatReply text(String text) {
        return new ChatReply(null, text, null, null);
    }

    // The whole message: the reply, then the card if there is one.
    public String text() {
        return card == null ? reply : reply + "\n\n" + card;
    }
}
