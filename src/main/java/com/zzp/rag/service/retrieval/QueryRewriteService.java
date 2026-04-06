package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.trace.ConversationTurn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class QueryRewriteService {

    private static final int MAX_QUERY_LENGTH = 180;
    private static final Pattern NOISE_PATTERN = Pattern.compile("^(请问|麻烦|帮我|你能|可以|请|帮忙)");

    /**
     * 规则式改写：在追问场景用最近一轮问题补全语义，避免检索因指代词失真。
     */
    public String rewrite(String question, List<ConversationTurn> history) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return "";
        }

        if (history == null || history.isEmpty() || !looksLikeFollowUp(normalizedQuestion)) {
            return truncate(normalizedQuestion);
        }

        ConversationTurn lastTurn = history.get(history.size() - 1);
        String contextQuestion = normalize(lastTurn.question());
        if (contextQuestion.isBlank()) {
            return truncate(normalizedQuestion);
        }

        String rewritten = "上文问题: " + contextQuestion + "；当前问题: " + normalizedQuestion;
        return truncate(rewritten);
    }

    private boolean looksLikeFollowUp(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("它")
                || lower.contains("这个")
                || lower.contains("那个")
                || lower.contains("上述")
                || lower.contains("上面")
                || lower.contains("继续")
                || lower.contains("然后")
                || lower.contains("怎么做")
                || lower.contains("如何做")
                || lower.contains("细说")
                || lower.contains("展开")
                || lower.startsWith("那")
                || lower.startsWith("再");
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text.trim().replaceAll("\\s+", " ");
        normalized = NOISE_PATTERN.matcher(normalized).replaceFirst("");
        return normalized.trim();
    }

    private String truncate(String query) {
        if (query.length() <= MAX_QUERY_LENGTH) {
            return query;
        }
        return query.substring(0, MAX_QUERY_LENGTH);
    }
}
