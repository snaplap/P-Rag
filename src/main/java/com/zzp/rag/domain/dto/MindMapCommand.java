package com.zzp.rag.domain.dto;

import java.util.Map;

/**
 * 前端可执行的思维导图命令。
 */
public record MindMapCommand(
                String tool,
                Map<String, Object> arguments) {
}
