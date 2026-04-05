package com.zzp.rag.domain.trace;

import java.time.Instant;

/**
 * 会话单轮记录。
 */
public record ConversationTurn(
                String question,
                String answer,
                Instant timestamp) {
}
