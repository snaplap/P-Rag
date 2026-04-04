package com.zzp.rag.domain.trace;

import java.time.Instant;

public record ConversationTurn(
        String question,
        String answer,
        Instant timestamp) {
}


