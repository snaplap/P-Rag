package com.zzp.rag.service;

import com.zzp.rag.domain.RetrievalChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public RetrievalService(EmbeddingService embeddingService, VectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public List<RetrievalChunk> retrieve(String question, int topK) {
        int safeTopK = Math.max(1, topK);
        double[] queryVector = embeddingService.embed(question);
        List<RetrievalChunk> candidates = vectorStore.search(queryVector, Math.max(12, safeTopK * 3));

        return rerank(question, candidates, safeTopK);
    }

    private List<RetrievalChunk> rerank(String question, List<RetrievalChunk> candidates, int topK) {
        List<RetrievalChunk> reranked = new ArrayList<>();
        for (RetrievalChunk candidate : candidates) {
            double overlap = tokenOverlapScore(question, candidate.content());
            double rerankScore = (candidate.score() * 0.7d) + (overlap * 0.3d);
            reranked.add(new RetrievalChunk(
                    candidate.id(),
                    candidate.documentId(),
                    candidate.content(),
                    rerankScore,
                    candidate.sourceType()));
        }

        return reranked.stream()
                .sorted(Comparator.comparingDouble(RetrievalChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private double tokenOverlapScore(String question, String content) {
        Set<String> qTokens = tokenSet(question);
        Set<String> cTokens = tokenSet(content);

        if (qTokens.isEmpty() || cTokens.isEmpty()) {
            return 0.0d;
        }

        int hit = 0;
        for (String token : qTokens) {
            if (cTokens.contains(token)) {
                hit++;
            }
        }
        return (double) hit / (double) qTokens.size();
    }

    private Set<String> tokenSet(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = text.toLowerCase().trim();
        String[] words = normalized.split("[^\\p{L}\\p{N}]+");
        Set<String> tokens = new HashSet<>();

        for (String word : words) {
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }

        if (!tokens.isEmpty()) {
            return tokens;
        }

        for (int i = 0; i < normalized.length() - 1; i++) {
            String bigram = normalized.substring(i, i + 2).trim();
            if (bigram.length() == 2) {
                tokens.add(bigram);
            }
        }
        return tokens;
    }
}
