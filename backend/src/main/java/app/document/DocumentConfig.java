package app.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

@Configuration
public class DocumentConfig {

    // The same provider as the chat model (app.assistant.provider).
    @Bean
    public EmbeddingClient embeddingClient(
            @Value("${app.assistant.provider}") String provider,
            @Value("${app.assistant.openai.api-key}") String apiKey,
            @Value("${app.assistant.openai.base-url}") String baseUrl,
            @Value("${app.documents.embedding-model}") String model,
            @Value("${app.documents.embedding-dimensions}") int dimensions,
            @Value("${app.documents.min-similarity}") double minSimilarity,
            JsonMapper jsonMapper
    ) {
        return switch (provider) {
            case "openai" -> new OpenAiEmbeddingClient(baseUrl, apiKey, model, dimensions, minSimilarity, jsonMapper);
            case "offline" -> new OfflineEmbeddingClient(dimensions);
            default -> throw new IllegalStateException("app.assistant.provider must be openai or offline, not " + provider);
        };
    }

    // pgvector on Postgres; plain Java on H2, which has no vector type.
    @Bean
    public DocumentSearch documentSearch(DataSource dataSource, JdbcTemplate jdbc,
                                         @Value("${app.documents.embedding-dimensions}") int dimensions)
            throws MetaDataAccessException {
        String database = JdbcUtils.extractDatabaseMetaData(dataSource, metaData -> metaData.getDatabaseProductName());
        return "PostgreSQL".equals(database) ? new PgVectorDocumentSearch(jdbc, dimensions) : new InMemoryDocumentSearch(jdbc);
    }
}
