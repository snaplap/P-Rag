package com.zzp.rag.service;

import com.zzp.rag.domain.KnowledgeBaseTrace;
import com.zzp.rag.mapper.KnowledgeUploadMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeTraceService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTraceService.class);

    private final KnowledgeUploadMapper knowledgeUploadMapper;

    public KnowledgeTraceService(KnowledgeUploadMapper knowledgeUploadMapper) {
        this.knowledgeUploadMapper = knowledgeUploadMapper;
    }

    public void save(String knowledgeBaseId, String sessionId, String documentId, String fileName, int chunkCount) {
        try {
            KnowledgeBaseTrace trace = new KnowledgeBaseTrace(
                    knowledgeBaseId,
                    sessionId,
                    documentId,
                    fileName,
                    chunkCount,
                    null);
            knowledgeUploadMapper.insert(trace);
        } catch (Exception ex) {
            log.warn("Failed to save knowledge upload trace: {}", ex.getMessage());
        }
    }

    public List<KnowledgeBaseTrace> listAll() {
        try {
            return knowledgeUploadMapper.listAll();
        } catch (Exception ex) {
            log.warn("Failed to list knowledge upload traces: {}", ex.getMessage());
            return List.of();
        }
    }
}
