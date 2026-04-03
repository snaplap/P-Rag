package com.zzp.rag.service;

import com.zzp.rag.domain.KnowledgeBaseTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeTraceService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTraceService.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeTraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String knowledgeBaseId, String sessionId, String documentId, String fileName, int chunkCount) {
        try {
            jdbcTemplate.update(
                    "insert into knowledge_upload(knowledge_base_id, session_id, document_id, file_name, chunk_count) values(?, ?, ?, ?, ?)",
                    knowledgeBaseId,
                    sessionId,
                    documentId,
                    fileName,
                    chunkCount);
        } catch (Exception ex) {
            log.warn("Failed to save knowledge upload trace: {}", ex.getMessage());
        }
    }

    public List<KnowledgeBaseTrace> listAll() {
        try {
            return jdbcTemplate.query(
                    "select knowledge_base_id, session_id, document_id, file_name, chunk_count, created_at from knowledge_upload order by id desc",
                    (rs, rowNum) -> new KnowledgeBaseTrace(
                            rs.getString("knowledge_base_id"),
                            rs.getString("session_id"),
                            rs.getString("document_id"),
                            rs.getString("file_name"),
                            rs.getInt("chunk_count"),
                            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString()));
        } catch (Exception ex) {
            log.warn("Failed to list knowledge upload traces: {}", ex.getMessage());
            return List.of();
        }
    }
}
