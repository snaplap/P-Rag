package com.zzp.rag.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求参数。
 */
public class QueryRequest {

    @NotBlank(message = "question不能为空")
    @Size(max = 2000, message = "question长度不能超过2000")
    private String question;

    @Size(max = 128, message = "sessionId长度不能超过128")
    private String sessionId;

    @Size(max = 128, message = "knowledgeBaseId长度不能超过128")
    private String knowledgeBaseId;

    private Integer topK;

    private Boolean enableMindMap;

    /**
     * 用户问题。
     */
    public String getQuestion() {
        return question;
    }

    /**
     * 设置用户问题。
     */
    public void setQuestion(String question) {
        this.question = question;
    }

    /**
     * 会话 ID。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话 ID。
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 检索条数 topK。
     */
    public Integer getTopK() {
        return topK;
    }

    /**
     * 设置检索条数 topK。
     */
    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    /**
     * 知识库 ID。
     */
    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    /**
     * 设置知识库 ID。
     */
    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    /**
     * 是否启用思维导图。
     */
    public Boolean getEnableMindMap() {
        return enableMindMap;
    }

    /**
     * 设置是否启用思维导图。
     */
    public void setEnableMindMap(Boolean enableMindMap) {
        this.enableMindMap = enableMindMap;
    }
}
