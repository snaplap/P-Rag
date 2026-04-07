package com.zzp.rag.service.retrieval;

/**
 * 远端向量存储不可用时抛出的运行时异常。
 */
public class RemoteVectorStoreException extends RuntimeException {

    public RemoteVectorStoreException(String message) {
        super(message);
    }

    public RemoteVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
