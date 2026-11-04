package app.assistant;

// The audio a spoken reply comes back as: MP3 for the browser, Ogg Opus for a Telegram voice
// note (sendVoice only shows Ogg Opus as a voice message).
public enum SpeechFormat {
    MP3("mp3"),
    OPUS("opus");

    private final String apiName;

    SpeechFormat(String apiName) {
        this.apiName = apiName;
    }

    // OpenAI's response_format value.
    public String apiName() {
        return apiName;
    }
}
