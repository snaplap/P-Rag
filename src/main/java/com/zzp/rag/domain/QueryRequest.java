package com.zzp.rag.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public Boolean getEnableMindMap() {
        return enableMindMap;
    }

    public void setEnableMindMap(Boolean enableMindMap) {
        this.enableMindMap = enableMindMap;
    }
}
