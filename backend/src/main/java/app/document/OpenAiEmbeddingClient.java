package app.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// OpenAI's embeddings endpoint. All of a document's chunks go in one call.
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    private final RestClient restClient;
    private final boolean configured;
    private final String model;
    private final int dimensions;
    private final double minSimilarity;
    private final JsonMapper jsonMapper;

    public OpenAiEmbeddingClient(String baseUrl, String apiKey, String model, int dimensions, double minSimilarity,
                                 JsonMapper jsonMapper) {
        this(buildRestClient(baseUrl, apiKey), apiKey != null && !apiKey.isBlank(), model, dimensions, minSimilarity, jsonMapper);
    }

    OpenAiEmbeddingClient(RestClient restClient, boolean configured, String model, int dimensions, double minSimilarity,
                          JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.configured = configured;
        this.model = model;
        this.dimensions = dimensions;
        this.minSimilarity = minSimilarity;
        this.jsonMapper = jsonMapper;
    }

    private static RestClient buildRestClient(String baseUrl, String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public double minSimilarity() {
        return minSimilarity;
    }

    @Override
    public Embeddings embed(List<String> texts) {
        if (!configured) {
            throw new EmbeddingException("OPENAI_API_KEY is missing on the server.");
        }
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", model);
        request.put("dimensions", dimensions);
        request.put("encoding_format", "float");
        texts.forEach(request.putArray("input")::add);

        long started = System.nanoTime();
        JsonNode body;
        try {
            body = jsonMapper.readTree(restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException exception) {
            // OpenAI's error body says why; it never contains the key.
            log.warn("OpenAI embeddings returned {}: {}", exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw new EmbeddingException("The embedding model returned an error (" + exception.getStatusCode().value() + ").");
        } catch (RestClientException exception) {
            log.warn("OpenAI embeddings could not be reached: {}", exception.getMessage());
            throw new EmbeddingException("The embedding model could not be reached.");
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) vectors.add(null);
        for (JsonNode item : body.path("data")) {
            JsonNode values = item.path("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = (float) values.get(i).asDouble();
            vectors.set(item.path("index").asInt(), vector);
        }
        if (vectors.stream().anyMatch(vector -> vector == null || vector.length != dimensions)) {
            throw new EmbeddingException("The embedding model returned " + body.path("data").size() + " vectors for "
                    + texts.size() + " texts, or the wrong size.");
        }
        long tokens = body.path("usage").path("total_tokens").asLong(0);
        log.info("OpenAI embeddings ({}): {} texts, {} tokens in {} ms", model, texts.size(), tokens,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return new Embeddings(vectors, tokens);
    }
}
