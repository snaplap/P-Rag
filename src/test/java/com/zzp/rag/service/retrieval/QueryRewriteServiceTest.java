package com.zzp.rag.service.retrieval;

import com.zzp.rag.domain.trace.ConversationTurn;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class QueryRewriteServiceTest {

    private final QueryRewriteService service = new QueryRewriteService();

    @Test
    void shouldKeepQuestionWhenNoHistory() {
        String rewritten = service.rewrite("请问 Milvus 混合检索怎么做", List.of());

        Assertions.assertEquals("Milvus 混合检索怎么做", rewritten);
    }

    @Test
    void shouldRewriteFollowUpQuestionWithLastTurnContext() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("Milvus 混合检索配置步骤", "回答", Instant.now()));

        String rewritten = service.rewrite("那它的索引参数怎么配", history);

        Assertions.assertTrue(rewritten.contains("上文问题: Milvus 混合检索配置步骤"));
        Assertions.assertTrue(rewritten.contains("当前问题: 那它的索引参数怎么配"));
    }

    @Test
    void shouldTruncateVeryLongRewrittenQuery() {
        String longQuestion = "继续" + "如何优化召回".repeat(60);
        List<ConversationTurn> history = List.of(
                new ConversationTurn("上一轮问题", "回答", Instant.now()));

        String rewritten = service.rewrite(longQuestion, history);

        Assertions.assertTrue(rewritten.length() <= 180);
    }
}
