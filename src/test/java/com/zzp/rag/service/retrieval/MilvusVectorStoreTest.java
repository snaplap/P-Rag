package com.zzp.rag.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.model.RetrievalChunk;
import com.zzp.rag.domain.model.VectorDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusVectorStoreTest {

    @Test
    void shouldThrowWhenMilvusBusinessFailedInStrictMode() throws Exception {
        RagProperties properties = baseProperties(true);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> createOk = httpResponse(200, "{\"code\":0}");
        HttpResponse<String> insertFail = httpResponse(200, "{\"code\":5001,\"message\":\"insert failed\"}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(createOk)
                .thenReturn(insertFail);

        MilvusVectorStore store = new MilvusVectorStore(properties, new ObjectMapper(), httpClient);

        Assertions.assertThrows(RemoteVectorStoreException.class,
                () -> store.upsert(doc("id-1", "kb-1", "doc-1", "content")));
    }

    @Test
    void shouldFallbackToLocalSearchWhenStrictModeDisabled() throws Exception {
        RagProperties properties = baseProperties(false);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> createOk = httpResponse(200, "{\"code\":0}");
        HttpResponse<String> insertFail = httpResponse(200, "{\"code\":3001,\"message\":\"insert failed\"}");
        HttpResponse<String> searchFail = httpResponse(200, "{\"code\":3002,\"message\":\"search failed\"}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(createOk)
                .thenReturn(insertFail)
                .thenReturn(searchFail);

        MilvusVectorStore store = new MilvusVectorStore(properties, new ObjectMapper(), httpClient);
        store.upsert(doc("id-1", "kb-1", "doc-1", "local content"));

        List<RetrievalChunk> chunks = store.search(new double[] { 0.1d, 0.2d }, 3, "kb-1");

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertEquals("doc-1", chunks.get(0).documentId());
    }

    @Test
    void shouldParseNestedSearchDataFromMilvusResponse() throws Exception {
        RagProperties properties = baseProperties(false);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> createOk = httpResponse(200, "{\"code\":0}");

        String nestedResponse = "{\"code\":0,\"data\":{\"data\":[{\"entity\":{\"id\":\"id-9\",\"knowledgeBaseId\":\"kb-1\",\"documentId\":\"doc-9\",\"content\":\"nested\"},\"distance\":0.2}]}}";
        HttpResponse<String> searchOk = httpResponse(200, nestedResponse);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(createOk)
                .thenReturn(searchOk);

        MilvusVectorStore store = new MilvusVectorStore(properties, new ObjectMapper(), httpClient);
        List<RetrievalChunk> chunks = store.search(new double[] { 0.1d, 0.2d }, 2, "kb-1");

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("doc-9", chunks.get(0).documentId());
        Assertions.assertEquals("nested", chunks.get(0).content());
    }

    @Test
    void shouldFallbackToBasicCreatePayloadWhenEnhancedCreateFailed() throws Exception {
        RagProperties properties = baseProperties(true);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> enhancedFail = httpResponse(200, "{\"code\":7001,\"message\":\"unsupported fields\"}");
        HttpResponse<String> basicCreateOk = httpResponse(200, "{\"code\":0}");
        HttpResponse<String> insertOk = httpResponse(200, "{\"code\":0}");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(enhancedFail)
                .thenReturn(basicCreateOk)
                .thenReturn(insertOk);

        MilvusVectorStore store = new MilvusVectorStore(properties, new ObjectMapper(), httpClient);
        store.upsert(doc("id-2", "kb-1", "doc-2", "content2"));

        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private RagProperties baseProperties(boolean strictMode) {
        RagProperties properties = new RagProperties();
        properties.getMilvus().setUseRemote(true);
        properties.getMilvus().setStrictMode(strictMode);
        properties.getMilvus().setBaseUrl("http://localhost:19530");
        properties.getMilvus().setCollection("rag_knowledge_chunks");
        properties.getEmbedding().setDimension(2);
        return properties;
    }

    private VectorDocument doc(String id, String kbId, String documentId, String content) {
        return new VectorDocument(id, kbId, documentId, content, new double[] { 0.1d, 0.2d });
    }

    private HttpResponse<String> httpResponse(int statusCode, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
