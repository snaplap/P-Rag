package com.zzp.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private final Stream stream = new Stream();
    private final Cache cache = new Cache();
    private final Session session = new Session();
    private final Embedding embedding = new Embedding();
    private final Retrieval retrieval = new Retrieval();
    private final Chunking chunking = new Chunking();
    private final Milvus milvus = new Milvus();
    private final Mcp mcp = new Mcp();

    public Stream getStream() {
        return stream;
    }

    public Cache getCache() {
        return cache;
    }

    public Session getSession() {
        return session;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public Chunking getChunking() {
        return chunking;
    }

    public Milvus getMilvus() {
        return milvus;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public static class Stream {
        private int chunkSize = 28;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }
    }

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

    public static class Embedding {
        private int dimension = 128;

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }
    }

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

    public static class Milvus {
        private String collection = "rag_knowledge_chunks";
        private boolean useRemote;
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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Mcp {
        private boolean useMock = true;

        public boolean isUseMock() {
            return useMock;
        }

        public void setUseMock(boolean useMock) {
            this.useMock = useMock;
        }
    }
}
