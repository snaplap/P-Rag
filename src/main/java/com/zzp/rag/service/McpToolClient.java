package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.MindMapCommand;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.WebSearchResult;

import java.util.List;

public interface McpToolClient {

    List<WebSearchResult> searchWeb(String question, int topK);

    MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence);
}
