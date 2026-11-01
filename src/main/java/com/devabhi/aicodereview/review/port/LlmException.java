package com.devabhi.aicodereview.review.port;

/** The model call failed (timeout, bad output after retry, ...). Messages must never contain PR code. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}