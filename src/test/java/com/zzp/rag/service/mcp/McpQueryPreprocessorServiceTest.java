package com.zzp.rag.service.mcp;

import com.zzp.rag.config.RagProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpQueryPreprocessorServiceTest {

    @Test
    void shouldPreprocessAndDecomposeComplexQuery() {
        RagProperties properties = new RagProperties();
        properties.getMcp().setEnableWebDecompose(true);
        properties.getMcp().setMaxWebSubQueries(3);

        McpQueryPreprocessorService service = new McpQueryPreprocessorService(properties);
        McpQueryPreprocessorService.ProcessedQuery result = service.preprocess("请对比 Milvus 和 Elasticsearch 优缺点");

        Assertions.assertFalse(result.cleanedQuery().isBlank());
        Assertions.assertTrue(result.candidateQueries().size() >= 2);
        Assertions.assertTrue(result.candidateQueries().stream().anyMatch(v -> v.contains("Milvus")));
    }

    @Test
    void shouldEnrichTemporalQueryWhenNoAbsoluteDate() {
        RagProperties properties = new RagProperties();
        McpQueryPreprocessorService service = new McpQueryPreprocessorService(properties);

        String enriched = service.enrichTemporal("最新 AI 新闻");

        Assertions.assertTrue(enriched.startsWith("最新 AI 新闻"));
        Assertions.assertTrue(enriched.contains("("));
        Assertions.assertTrue(enriched.contains(")"));
    }

    @Test
    void shouldNormalizeUrlForDedup() {
        RagProperties properties = new RagProperties();
        McpQueryPreprocessorService service = new McpQueryPreprocessorService(properties);

        String url1 = service.normalizeUrl("https://example.com/news/?utm_source=a&spm=1#part");
        String url2 = service.normalizeUrl("https://example.com/news");

        Assertions.assertEquals(url2, url1);
    }
}
