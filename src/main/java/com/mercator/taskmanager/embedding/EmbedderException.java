package com.mercator.taskmanager.embedding;

/** Ошибка обращения к сервису эмбеддингов. */
public class EmbedderException extends RuntimeException {

    public EmbedderException(String message) {
        super(message);
    }

    public EmbedderException(String message, Throwable cause) {
        super(message, cause);
    }
}