package com.zzp.rag.service.mcp;

import com.zzp.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class McpQueryPreprocessorService {

    private final RagProperties ragProperties;

    public McpQueryPreprocessorService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public ProcessedQuery preprocess(String query) {
        String cleaned = cleanQuery(query);
        String enriched = enrichTemporal(cleaned);

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(enriched);

        if (ragProperties.getMcp().isEnableWebDecompose()) {
            candidates.addAll(decompose(enriched));
        }

        int maxSubQueries = Math.max(1, ragProperties.getMcp().getMaxWebSubQueries());
        List<String> limited = candidates.stream()
                .filter(v -> v != null && !v.isBlank())
                .limit(maxSubQueries)
                .toList();

        return new ProcessedQuery(enriched, limited);
    }

    public String cleanQuery(String query) {
        if (query == null) {
            return "";
        }

        String normalized = query.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[？?]{2,}", "?")
                .replaceAll("[！!]{2,}", "!")
                .replaceAll("[。\\.]{2,}", ".")
                .replaceAll("[，,]{2,}", ",");

        return normalized;
    }

    public String enrichTemporal(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String lower = query.toLowerCase(Locale.ROOT);
        boolean temporal = lower.contains("最新")
                || lower.contains("当前")
                || lower.contains("今天")
                || lower.contains("实时")
                || lower.contains("now")
                || lower.contains("today");
        boolean hasAbsoluteDate = query.matches(".*(19|20)\\d{2}[-年/.].*");

        if (!temporal || hasAbsoluteDate) {
            return query;
        }

        return query + " (" + LocalDate.now() + ")";
    }

    public List<String> decompose(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String lower = query.toLowerCase(Locale.ROOT);
        boolean complex = lower.contains("对比")
                || lower.contains("比较")
                || lower.contains("以及")
                || lower.contains("并且")
                || lower.contains("同时")
                || lower.contains(" and ")
                || lower.contains(" vs ");
        if (!complex) {
            return List.of();
        }

        String intent = inferIntent(query);
        Set<String> entities = extractEntities(query);
        if (entities.size() < 2) {
            return List.of();
        }

        List<String> subQueries = new ArrayList<>();
        for (String entity : entities) {
            subQueries.add(entity + " " + intent);
        }
        return subQueries;
    }

    private Set<String> extractEntities(String query) {
        String cleaned = query
                .replace("对比", " ")
                .replace("比较", " ")
                .replace("以及", " ")
                .replace("并且", " ")
                .replace("同时", " ")
                .replace("和", " ")
                .replace("与", " ")
                .replace("vs", " ")
                .replace("VS", " ")
                .replace("/", " ")
                .replace("、", " ");

        LinkedHashSet<String> entities = new LinkedHashSet<>();
        for (String token : cleaned.split("[\\s,，;；]+")) {
            String t = token.trim();
            if (t.length() >= 2 && t.length() <= 24) {
                entities.add(t);
            }
        }
        return entities;
    }

    private String inferIntent(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("优缺点") || lower.contains("优势") || lower.contains("劣势")) {
            return "优缺点";
        }
        if (lower.contains("区别") || lower.contains("差异")) {
            return "区别";
        }
        return "关键信息";
    }

    public String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("#.*$", "");
        normalized = normalized.replaceAll("[?&](utm_[^=&]+|spm|from|ref)=[^&]*", "");
        normalized = normalized.replaceAll("[?&]+$", "");
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record ProcessedQuery(String cleanedQuery, List<String> candidateQueries) {
    }
}
