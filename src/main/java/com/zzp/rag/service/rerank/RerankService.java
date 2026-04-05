package com.zzp.rag.service.rerank;

import com.zzp.rag.domain.model.RetrievalChunk;

import java.util.List;

/**
 * 重排服务接口。
 */
public interface RerankService {

    /**
     * 对候选检索片段按问题相关性重新排序。
     */
    List<RetrievalChunk> rerank(String question, List<RetrievalChunk> candidates);
}
