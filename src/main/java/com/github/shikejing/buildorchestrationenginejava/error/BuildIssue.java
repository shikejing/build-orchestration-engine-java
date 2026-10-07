package com.github.shikejing.buildorchestrationenginejava.error;

public record BuildIssue(
        String targetName,
        String message
) {
}
