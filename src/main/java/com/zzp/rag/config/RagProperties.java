package com.zzp.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
/**
 * RAG 模块配置聚合对象。
 */
public class RagProperties {

    private final Llm llm = new Llm();
    private final Stream stream = new Stream();
    private final Cache cache = new Cache();
    private final Session session = new Session();
    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final Chunking chunking = new Chunking();
    private final Milvus milvus = new Milvus();
    private final Mcp mcp = new Mcp();
    private final Metrics metrics = new Metrics();

    /**
     * 大模型与重排相关配置。
     */
    public Llm getLlm() {
        return llm;
    }

    /**
     * SSE 流式输出配置。
     */
    public Stream getStream() {
        return stream;
    }

    /**
     * 答案缓存配置。
     */
    public Cache getCache() {
        return cache;
    }

    /**
     * 会话历史配置。
     */
    public Session getSession() {
        return session;
    }

    /**
     * 向量维度配置。
     */
    public Embedding getEmbedding() {
        return embedding;
    }

    /**
     * 检索与阈值配置。
     */
    public Retrieval getRetrieval() {
        return retrieval;
    }

    /**
     * 文档切片配置。
     */
    public Chunking getChunking() {
        return chunking;
    }

    /**
     * Milvus 存储配置。
     */
    public Milvus getMilvus() {
        return milvus;
    }

    /**
     * MCP 工具调用配置。
     */
    public Mcp getMcp() {
        return mcp;
    }

    /**
     * 成本估算配置。
     */
    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * 流式输出参数。
     */
    public static class Stream {
        private int chunkSize = 28;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }
    }

    /**
     * LLM、Embedding 与 Rerank 相关参数。
     */
    public static class Llm {
        private String provider = "dashscope";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "qwen-plus";
        private String embeddingModel = "text-embedding-v3";
        private String rerankModel = "gte-rerank-v2";
        private String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private String rerankApiKey = "";
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 60000;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getRerankModel() {
            return rerankModel;
        }

        public void setRerankModel(String rerankModel) {
            this.rerankModel = rerankModel;
        }

        public String getRerankUrl() {
            return rerankUrl;
        }

        public void setRerankUrl(String rerankUrl) {
            this.rerankUrl = rerankUrl;
        }

        public String getRerankApiKey() {
            return rerankApiKey;
        }

        public void setRerankApiKey(String rerankApiKey) {
            this.rerankApiKey = rerankApiKey;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /**
     * 答案缓存参数。
     */
    public static class Cache {
        private String keyPrefix = "rag:answer:";
        private long ttlSeconds = 900;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /**
     * 会话缓存参数。
     */
    public static class Session {
        private String keyPrefix = "rag:session:";
        private long ttlSeconds = 1800;
        private int maxHistory = 3;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getMaxHistory() {
            return maxHistory;
        }

        public void setMaxHistory(int maxHistory) {
            this.maxHistory = maxHistory;
        }
    }

    /**
     * 向量维度参数。
     */
    public static class Embedding {
        private int dimension = 1024;
        private int batchSize = 10;
        private boolean cacheEnabled = true;
        private long cacheTtlSeconds = 604800;

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }

    /**
     * 检索参数。
     */
    public static class Retrieval {
        private int defaultTopK = 5;
        private double minScore = 0.45d;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }

    /**
     * 分块参数。
     */
    public static class Chunking {
        private int maxChars = 500;
        private int overlapChars = 80;

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public int getOverlapChars() {
            return overlapChars;
        }

        public void setOverlapChars(int overlapChars) {
            this.overlapChars = overlapChars;
        }
    }

    /**
     * Milvus 参数。
     */
    public static class Milvus {
        private String collection = "rag_knowledge_chunks";
        private boolean useRemote;
        private boolean strictMode = false;
        private String baseUrl = "http://localhost:19530";

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public boolean isUseRemote() {
            return useRemote;
        }

        public void setUseRemote(boolean useRemote) {
            this.useRemote = useRemote;
        }

        public boolean isStrictMode() {
            return strictMode;
        }

        public void setStrictMode(boolean strictMode) {
            this.strictMode = strictMode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * MCP 参数。
     */
    public static class Mcp {
        private boolean useMock = true;
        private String webSearchUrl = "http://localhost:18080/mcp/web-search/search";
        private String diagramUrl = "http://localhost:18081/mcp/diagram";
        private boolean enableKrokiRelay = true;
        private String krokiBaseUrl = "http://localhost:18083";
        private int krokiTimeoutMs = 5000;
        private int callTimeoutMs = 6000;
        private int maxRetries = 2;
        private int retryBackoffMs = 250;
        private int circuitBreakerThreshold = 3;
        private int circuitBreakerOpenMs = 30000;
        private boolean enableWebPreprocess = true;
        private boolean enableWebDecompose = true;
        private int maxWebSubQueries = 3;
        private boolean enableMindMapPreprocess = true;
        private int mindMapMaxAnswerChars = 320;
        private int mindMapMaxEvidenceItems = 8;
        private int mindMapSnippetChars = 120;

        public boolean isUseMock() {
            return useMock;
        }

        public void setUseMock(boolean useMock) {
            this.useMock = useMock;
        }

        public String getWebSearchUrl() {
            return webSearchUrl;
        }

        public void setWebSearchUrl(String webSearchUrl) {
            this.webSearchUrl = webSearchUrl;
        }

        public String getDiagramUrl() {
            return diagramUrl;
        }

        public void setDiagramUrl(String diagramUrl) {
            this.diagramUrl = diagramUrl;
        }

        public boolean isEnableKrokiRelay() {
            return enableKrokiRelay;
        }

        public void setEnableKrokiRelay(boolean enableKrokiRelay) {
            this.enableKrokiRelay = enableKrokiRelay;
        }

        public String getKrokiBaseUrl() {
            return krokiBaseUrl;
        }

        public void setKrokiBaseUrl(String krokiBaseUrl) {
            this.krokiBaseUrl = krokiBaseUrl;
        }

        public int getKrokiTimeoutMs() {
            return krokiTimeoutMs;
        }

        public void setKrokiTimeoutMs(int krokiTimeoutMs) {
            this.krokiTimeoutMs = krokiTimeoutMs;
        }

        public int getCallTimeoutMs() {
            return callTimeoutMs;
        }

        public void setCallTimeoutMs(int callTimeoutMs) {
            this.callTimeoutMs = callTimeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(int retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }

        public int getCircuitBreakerThreshold() {
            return circuitBreakerThreshold;
        }

        public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
            this.circuitBreakerThreshold = circuitBreakerThreshold;
        }

        public int getCircuitBreakerOpenMs() {
            return circuitBreakerOpenMs;
        }

        public void setCircuitBreakerOpenMs(int circuitBreakerOpenMs) {
            this.circuitBreakerOpenMs = circuitBreakerOpenMs;
        }

        public boolean isEnableWebPreprocess() {
            return enableWebPreprocess;
        }

        public void setEnableWebPreprocess(boolean enableWebPreprocess) {
            this.enableWebPreprocess = enableWebPreprocess;
        }

        public boolean isEnableWebDecompose() {
            return enableWebDecompose;
        }

        public void setEnableWebDecompose(boolean enableWebDecompose) {
            this.enableWebDecompose = enableWebDecompose;
        }

        public int getMaxWebSubQueries() {
            return maxWebSubQueries;
        }

        public void setMaxWebSubQueries(int maxWebSubQueries) {
            this.maxWebSubQueries = maxWebSubQueries;
        }

        public boolean isEnableMindMapPreprocess() {
            return enableMindMapPreprocess;
        }

        public void setEnableMindMapPreprocess(boolean enableMindMapPreprocess) {
            this.enableMindMapPreprocess = enableMindMapPreprocess;
        }

        public int getMindMapMaxAnswerChars() {
            return mindMapMaxAnswerChars;
        }

        public void setMindMapMaxAnswerChars(int mindMapMaxAnswerChars) {
            this.mindMapMaxAnswerChars = mindMapMaxAnswerChars;
        }

        public int getMindMapMaxEvidenceItems() {
            return mindMapMaxEvidenceItems;
        }

        public void setMindMapMaxEvidenceItems(int mindMapMaxEvidenceItems) {
            this.mindMapMaxEvidenceItems = mindMapMaxEvidenceItems;
        }

        public int getMindMapSnippetChars() {
            return mindMapSnippetChars;
        }

        public void setMindMapSnippetChars(int mindMapSnippetChars) {
            this.mindMapSnippetChars = mindMapSnippetChars;
        }
    }

    /**
     * 成本估算参数。
     */
    public static class Metrics {
        private double inputCostPer1kTokens = 0.0d;
        private double outputCostPer1kTokens = 0.0d;

        public double getInputCostPer1kTokens() {
            return inputCostPer1kTokens;
        }

        public void setInputCostPer1kTokens(double inputCostPer1kTokens) {
            this.inputCostPer1kTokens = inputCostPer1kTokens;
        }

        public double getOutputCostPer1kTokens() {
            return outputCostPer1kTokens;
        }

        public void setOutputCostPer1kTokens(double outputCostPer1kTokens) {
            this.outputCostPer1kTokens = outputCostPer1kTokens;
        }
    }
}
