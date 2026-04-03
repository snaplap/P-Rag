package com.zzp.rag.domain;

import java.util.Map;

public record MindMapCommand(
        String tool,
        Map<String, Object> arguments) {
}
