package com.zzp.rag.service.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeRerankService implements RerankService {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public DashScopeRerankService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RetrievalChunk> rerank(String question, List<RetrievalChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        RagProperties.Llm llm = ragProperties.getLlm();
        String endpoint = llm.getRerankUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return candidates;
        }

        String apiKey = resolveApiKey(llm);
        if (apiKey.isBlank()) {
            return candidates;
        }

        try {
            List<String> documents = candidates.stream().map(RetrievalChunk::content).toList();

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", question == null ? "" : question);
            input.put("documents", documents);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", llm.getRerankModel());
            payload.put("input", input);

            String body = objectMapper.writeValueAsString(payload);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1000, llm.getConnectTimeoutMs())))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(3000, llm.getReadTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return candidates;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("output").path("results");
            if ((!results.isArray() || results.isEmpty()) && root.path("data").isArray()) {
                results = root.path("data");
            }
            if (!results.isArray() || results.isEmpty()) {
                return candidates;
            }

            List<RetrievalChunk> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }

                double score = result.path("relevance_score").asDouble(Double.NaN);
                if (Double.isNaN(score)) {
                    score = result.path("score").asDouble(candidates.get(index).score());
                }

                RetrievalChunk original = candidates.get(index);
                reranked.add(new RetrievalChunk(
                        original.id(),
                        original.documentId(),
                        original.content(),
                        score,
                        original.sourceType()));
            }

            if (reranked.isEmpty()) {
                return candidates;
            }
            return reranked;
        } catch (Exception ex) {
            return candidates;
        }
    }

    private String resolveApiKey(RagProperties.Llm llm) {
        String rerankApiKey = llm.getRerankApiKey() == null ? "" : llm.getRerankApiKey().trim();
        if (!rerankApiKey.isBlank()) {
            return rerankApiKey;
        }
        return llm.getApiKey() == null ? "" : llm.getApiKey().trim();
    }
}
