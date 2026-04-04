package com.zzp.rag.domain.dto;

public record WebSearchResult(
        String title,
        String url,
        String snippet,
        double confidence) {
}


