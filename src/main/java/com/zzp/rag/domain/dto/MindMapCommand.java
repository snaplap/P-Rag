package com.zzp.rag.domain.dto;

import java.util.Map;

public record MindMapCommand(
        String tool,
        Map<String, Object> arguments) {
}


