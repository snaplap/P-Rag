package com.zzp.rag.controller;

import com.zzp.rag.config.RagProperties;
import com.zzp.rag.domain.IngestResult;
import com.zzp.rag.domain.KnowledgeBaseTrace;
import com.zzp.rag.domain.QueryRequest;
import com.zzp.rag.domain.RagAnswer;
import com.zzp.rag.service.KnowledgeTraceService;
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
import java.time.Instant;
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
    private final KnowledgeTraceService knowledgeTraceService;
    private final RagProperties ragProperties;

    public RagController(
            RagOrchestratorService ragOrchestratorService,
            MarkdownIngestionService markdownIngestionService,
            KnowledgeTraceService knowledgeTraceService,
            RagProperties ragProperties) {
        this.ragOrchestratorService = ragOrchestratorService;
        this.markdownIngestionService = markdownIngestionService;
        this.knowledgeTraceService = knowledgeTraceService;
        this.ragProperties = ragProperties;
    }

    @PostMapping("/query")
    public RagAnswer query(@Valid @RequestBody QueryRequest request) {
        // 非流式接口：用于脚本调试或简单调用。
        return ragOrchestratorService.answer(request);
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(0L);

        CompletableFuture.runAsync(() -> {
            try {
                RagAnswer answer = ragOrchestratorService.answer(request);
                // 先发送文本增量分片，前端可边接收边渲染。
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
                finalPayload.put("logMetrics", buildLogMetricsPayload(request, answer));
                // 最终事件包含来源、评估和思维导图调用指令，便于前端一次性收尾展示。
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
        // 首版仅支持 Markdown，读取后交给摄入服务做切片与向量化。
        String markdown = new String(file.getBytes(), StandardCharsets.UTF_8);
        return markdownIngestionService.ingestMarkdown(markdown, documentId, file.getOriginalFilename());
    }

    @GetMapping("/knowledge-bases")
    public List<KnowledgeBaseTrace> listKnowledgeBases() {
        return knowledgeTraceService.listAll();
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
        if (sourceType == null) {
            return "知识库";
        }
        return switch (sourceType) {
            case KNOWLEDGE_BASE -> "知识库";
            case WEB -> "联网";
            case HYBRID -> "知识库+联网";
        };
    }

    private Map<String, Object> buildLogMetricsPayload(QueryRequest request, RagAnswer answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> sessionInfo = new LinkedHashMap<>();

        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? "anonymous"
                : request.getSessionId().trim();
        String knowledgeBaseId = request.getKnowledgeBaseId() == null || request.getKnowledgeBaseId().isBlank()
                ? "-"
                : request.getKnowledgeBaseId().trim();
        String fileName = knowledgeTraceService.findLatestByKnowledgeBaseId(knowledgeBaseId)
                .map(v -> v.fileName() == null || v.fileName().isBlank() ? v.knowledgeBaseId() : v.fileName())
                .orElse(knowledgeBaseId);

        sessionInfo.put("会话ID", sessionId);
        sessionInfo.put("知识库ID", knowledgeBaseId);
        sessionInfo.put("文件名", fileName);
        sessionInfo.put("问题简述", summarizeQuestion(request.getQuestion()));
        sessionInfo.put("请求时间", Instant.now().toString());
        payload.put("会话信息", sessionInfo);

        if (answer.logMetrics() != null && !answer.logMetrics().isEmpty()) {
            payload.putAll(answer.logMetrics());
        }
        return payload;
    }

    private String summarizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "-";
        }
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 42 ? normalized : normalized.substring(0, 42) + "...";
    }
}
