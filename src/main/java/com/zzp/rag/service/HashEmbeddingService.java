package com.zzp.rag.service;

import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Service;

@Service
public class HashEmbeddingService implements EmbeddingService {

    private final RagProperties ragProperties;

    public HashEmbeddingService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public double[] embed(String text) {
        int dim = Math.max(16, ragProperties.getEmbedding().getDimension());
        double[] vector = new double[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String normalized = text.toLowerCase();
        String[] tokens = normalized.split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), dim);
            vector[index] += 1.0d;
        }

        double norm = 0.0d;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0.0d) {
            return vector;
        }
        double scale = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / scale;
        }
        return vector;
    }
}
