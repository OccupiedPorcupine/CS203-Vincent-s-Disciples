package app.assistant.guide;

// Chooses which parts of the app guide to put in the model's instructions for a message.
// The model can always fetch any other section with the get_guide tool. A vector-search
// implementation can replace IndexGuideRetriever without changing the prompt or tools (#33).
public interface GuideRetriever {

    String context(String userText, String currentPage);
}
