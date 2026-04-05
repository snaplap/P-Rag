package com.zzp.rag.service.chunking;

import java.util.List;

/**
 * 结构化分块模型，包含正文、标题路径、顺序索引与前后片段索引引用。
 */
public record Chunk(String content, String titlePath, int chunkIndex, Integer prevIndex, Integer nextIndex) {

    public Chunk(String content, String titlePath, int chunkIndex) {
        this(content, titlePath, chunkIndex, null, null);
    }

    public Chunk getPrev(List<Chunk> allChunks) {
        if (allChunks == null || prevIndex == null) {
            return null;
        }
        if (prevIndex < 0 || prevIndex >= allChunks.size()) {
            return null;
        }
        return allChunks.get(prevIndex);
    }

    public Chunk getNext(List<Chunk> allChunks) {
        if (allChunks == null || nextIndex == null) {
            return null;
        }
        if (nextIndex < 0 || nextIndex >= allChunks.size()) {
            return null;
        }
        return allChunks.get(nextIndex);
    }
}