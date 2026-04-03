package com.zzp.rag.domain;

import java.time.Instant;

public record ConversationTurn(
        String question,
        String answer,
        Instant timestamp) {
}
