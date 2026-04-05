package com.zzp.rag.service.embedding;

/**
 * 向量化服务接口。
 */
public interface EmbeddingService {

    /**
     * 将文本转为向量。
     */
    double[] embed(String text);
}
