package com.zzp.rag.service.mcp;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.dto.WebSearchResult;

import java.util.List;

public interface McpToolClient {

    List<WebSearchResult> searchWeb(String question, int topK);

    MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence);
}


