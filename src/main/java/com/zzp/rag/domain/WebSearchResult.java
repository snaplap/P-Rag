package com.zzp.rag.domain;

public record WebSearchResult(
        String title,
        String url,
        String snippet,
        double confidence) {
}
