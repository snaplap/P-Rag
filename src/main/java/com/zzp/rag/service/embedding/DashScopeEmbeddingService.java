package com.zzp.rag.service.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
/**
 * DashScope/OpenAI 兼容接口的向量化实现。
 */
public class DashScopeEmbeddingService implements EmbeddingService {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public DashScopeEmbeddingService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用远端 embedding API 生成向量。
     */
    @Override
    public double[] embed(String text) {
        int dim = Math.max(1, ragProperties.getEmbedding().getDimension());
        if (text == null || text.isBlank()) {
            return new double[dim];
        }

        RagProperties.Llm llm = ragProperties.getLlm();
        String apiKey = llm.getApiKey() == null ? "" : llm.getApiKey().trim();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("LLM_API_KEY/DASHSCOPE_API_KEY is required for embedding");
        }

        try {
            String endpoint = llm.getBaseUrl().endsWith("/")
                    ? llm.getBaseUrl() + "embeddings"
                    : llm.getBaseUrl() + "/embeddings";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", llm.getEmbeddingModel());
            payload.put("input", text);

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
                throw new IllegalStateException("Embedding API status=" + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding API returns empty data");
            }

            JsonNode vectorNode = data.get(0).path("embedding");
            if (!vectorNode.isArray() || vectorNode.isEmpty()) {
                throw new IllegalStateException("Embedding vector not found");
            }

            double[] vector = new double[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = vectorNode.get(i).asDouble(0.0d);
            }
            return reshape(vector, dim);
        } catch (Exception ex) {
            throw new IllegalStateException("Call DashScope embedding failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 向量维度对齐：不足补零，超出截断。
     */
    private double[] reshape(double[] source, int targetDim) {
        if (source.length == targetDim) {
            return source;
        }

        double[] resized = new double[targetDim];
        int copy = Math.min(source.length, targetDim);
        System.arraycopy(source, 0, resized, 0, copy);
        return resized;
    }
}
