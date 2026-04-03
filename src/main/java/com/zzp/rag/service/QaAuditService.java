package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QaAuditService {

    private static final Logger log = LoggerFactory.getLogger(QaAuditService.class);

    private final JdbcTemplate jdbcTemplate;

    public QaAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void safeInsert(String sessionId, String question, DataSourceType source, boolean uncertain,
            boolean cacheHit) {
        try {
            jdbcTemplate.update(
                    "insert into qa_audit(session_id, question, source, uncertain, cache_hit) values(?, ?, ?, ?, ?)",
                    sessionId,
                    question,
                    source.name(),
                    uncertain,
                    cacheHit);
        } catch (Exception ex) {
            log.warn("Failed to persist qa audit record: {}", ex.getMessage());
        }
    }
}
