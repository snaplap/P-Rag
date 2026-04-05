package com.zzp.rag.service.mcp;

import com.zzp.rag.domain.model.DataSourceType;
import com.zzp.rag.domain.dto.MindMapCommand;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.dto.WebSearchResult;

import java.util.List;

/**
 * MCP 工具客户端抽象。
 */
public interface McpToolClient {

    /**
     * 调用联网搜索。
     */
    List<WebSearchResult> searchWeb(String question, int topK);

    /**
     * 生成思维导图命令。
     */
    MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence);
}
