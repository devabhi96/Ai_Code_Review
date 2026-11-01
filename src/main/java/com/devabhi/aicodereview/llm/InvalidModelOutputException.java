package com.devabhi.aicodereview.llm;

/** Model output that does not match the schema. The message is fixed text, never model output. */
final class InvalidModelOutputException extends RuntimeException {

    InvalidModelOutputException(String message) {
        super(message);
    }
}