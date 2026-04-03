package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.domain.MindMapCommand;
import com.zzp.rag.domain.RetrievalChunk;
import com.zzp.rag.domain.WebSearchResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockMcpToolClient implements McpToolClient {

    @Override
    public List<WebSearchResult> searchWeb(String question, int topK) {
        int safeTopK = Math.max(1, topK);
        return List.of(
                new WebSearchResult(
                        "MockSearch-1",
                        "https://example.com/search/1",
                        "联网检索模拟结果：" + question + "（结果1）",
                        0.82d),
                new WebSearchResult(
                        "MockSearch-2",
                        "https://example.com/search/2",
                        "联网检索模拟结果：" + question + "（结果2）",
                        0.73d),
                new WebSearchResult(
                        "MockSearch-3",
                        "https://example.com/search/3",
                        "联网检索模拟结果：" + question + "（结果3）",
                        0.66d))
                .stream().limit(safeTopK).toList();
    }

    @Override
    public MindMapCommand generateMindMap(String question, String answer, DataSourceType sourceType,
            List<RetrievalChunk> evidence) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("topic", question);
        arguments.put("source", sourceType.name());
        arguments.put("summary", safeSnippet(answer));
        arguments.put("evidenceCount", evidence == null ? 0 : evidence.size());

        return new MindMapCommand("mindmap.generate", arguments);
    }

    private String safeSnippet(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        int max = Math.min(180, answer.length());
        return answer.substring(0, max);
    }
}
