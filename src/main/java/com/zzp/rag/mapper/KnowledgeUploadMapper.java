package com.zzp.rag.mapper;

import com.zzp.rag.domain.KnowledgeBaseTrace;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeUploadMapper {

    @Insert("insert into knowledge_upload(knowledge_base_id, session_id, document_id, file_name, chunk_count) values(#{knowledgeBaseId}, #{sessionId}, #{documentId}, #{fileName}, #{chunkCount})")
    void insert(KnowledgeBaseTrace trace);

    @Select("select knowledge_base_id as knowledgeBaseId, session_id as sessionId, document_id as documentId, file_name as fileName, chunk_count as chunkCount, cast(created_at as varchar) as createdAt from knowledge_upload order by id desc")
    List<KnowledgeBaseTrace> listAll();
}
