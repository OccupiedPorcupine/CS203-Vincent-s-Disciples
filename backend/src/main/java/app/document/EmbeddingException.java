package app.document;

// The embedding model couldn't be reached or refused the request. A document is then saved
// with index_status FAILED, and the assistant answers without documents.
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }
}
