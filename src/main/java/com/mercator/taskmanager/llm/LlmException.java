package com.mercator.taskmanager.llm;

/**
 * Ошибка обращения к нейросети.
 * kind: AUTH — авторизация/оплата или не заданы ключи; TIMEOUT — таймаут; OTHER — прочее.
 */
public class LlmException extends RuntimeException {

    public enum Kind { AUTH, TIMEOUT, OTHER }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}