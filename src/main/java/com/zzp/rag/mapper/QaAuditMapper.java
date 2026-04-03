package com.zzp.rag.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QaAuditMapper {

    @Insert("insert into qa_audit(session_id, question, source, uncertain, cache_hit) values(#{sessionId}, #{question}, #{source}, #{uncertain}, #{cacheHit})")
    void insert(
            @Param("sessionId") String sessionId,
            @Param("question") String question,
            @Param("source") String source,
            @Param("uncertain") boolean uncertain,
            @Param("cacheHit") boolean cacheHit);
}
