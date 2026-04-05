package com.zzp.rag.domain.dto;

/**
 * 联网搜索单条结果。
 */
public record WebSearchResult(
                String title,
                String url,
                String snippet,
                double confidence) {
}
