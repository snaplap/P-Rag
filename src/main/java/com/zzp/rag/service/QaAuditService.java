package com.zzp.rag.service;

import com.zzp.rag.domain.DataSourceType;
import com.zzp.rag.mapper.QaAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QaAuditService {

    private static final Logger log = LoggerFactory.getLogger(QaAuditService.class);

    private final QaAuditMapper qaAuditMapper;

    public QaAuditService(QaAuditMapper qaAuditMapper) {
        this.qaAuditMapper = qaAuditMapper;
    }

    public void safeInsert(String sessionId, String question, DataSourceType source, boolean uncertain,
            boolean cacheHit) {
        try {
            qaAuditMapper.insert(sessionId, question, source.name(), uncertain, cacheHit);
        } catch (Exception ex) {
            log.warn("Failed to persist qa audit record: {}", ex.getMessage());
        }
    }
}
