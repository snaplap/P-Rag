package com.zzp.rag.controller;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.IngestResult;
import com.zzp.rag.domain.QueryRequest;
import com.zzp.rag.domain.RagAnswer;
import com.zzp.rag.service.MarkdownIngestionService;
import com.zzp.rag.service.RagOrchestratorService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/rag")
@Validated
public class RagController {

    private final RagOrchestratorService ragOrchestratorService;
    private final MarkdownIngestionService markdownIngestionService;
    private final RagProperties ragProperties;

    public RagController(
            RagOrchestratorService ragOrchestratorService,
            MarkdownIngestionService markdownIngestionService,
            RagProperties ragProperties) {
        this.ragOrchestratorService = ragOrchestratorService;
        this.markdownIngestionService = markdownIngestionService;
        this.ragProperties = ragProperties;
    }

    @PostMapping("/query")
    public RagAnswer query(@Valid @RequestBody QueryRequest request) {
        return ragOrchestratorService.answer(request);
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        CompletableFuture.runAsync(() -> {
            try {
                RagAnswer answer = ragOrchestratorService.answer(request);
                for (String chunk : chunk(answer.answer(), ragProperties.getStream().getChunkSize())) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", "text");
                    payload.put("content", chunk);
                    payload.put("source", sourceLabel(answer.dataSource()));
                    emitter.send(SseEmitter.event().name("chunk").data(payload));
                }

                Map<String, Object> finalPayload = new LinkedHashMap<>();
                finalPayload.put("type", "final");
                finalPayload.put("question", answer.question());
                finalPayload.put("source", sourceLabel(answer.dataSource()));
                finalPayload.put("uncertain", answer.uncertain());
                finalPayload.put("cacheHit", answer.cacheHit());
                finalPayload.put("references", answer.references());
                finalPayload.put("evaluation", answer.evaluation());
                finalPayload.put("mindMapCommand", answer.mindMapCommand());
                emitter.send(SseEmitter.event().name("final").data(finalPayload));

                emitter.complete();
            } catch (Exception ex) {
                try {
                    Map<String, Object> error = Map.of(
                            "type", "error",
                            "message", ex.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(error));
                } catch (IOException ignored) {
                    // ignore
                }
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    @PostMapping(value = "/ingest/markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResult ingestMarkdown(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "documentId", required = false) String documentId) throws IOException {
        String markdown = new String(file.getBytes(), StandardCharsets.UTF_8);
        return markdownIngestionService.ingestMarkdown(markdown, documentId);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    private List<String> chunk(String text, int chunkSize) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int safeSize = Math.max(8, chunkSize);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + safeSize, text.length());
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private String sourceLabel(com.zzp.rag.domain.DataSourceType sourceType) {
        return sourceType == com.zzp.rag.domain.DataSourceType.KNOWLEDGE_BASE ? "知识库" : "联网";
    }
}
