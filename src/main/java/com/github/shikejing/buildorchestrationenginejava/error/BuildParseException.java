package com.github.shikejing.buildorchestrationenginejava.error;

public class BuildParseException extends RuntimeException {
    public BuildParseException(String message) {
        super(message);
    }

    public BuildParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
