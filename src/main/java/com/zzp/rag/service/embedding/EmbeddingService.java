package com.zzp.rag.service.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务接口。
 */
public interface EmbeddingService {

    /**
     * 将文本转为向量。
     */
    double[] embed(String text);

    /**
     * 批量向量化，默认回退为逐条调用。
     */
    default List<double[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<double[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
