package com.github.shikejing.buildorchestrationenginejava.error;

import java.util.List;
import java.util.stream.Collectors;

public class BuildValidationException extends RuntimeException {
    private final List<BuildIssue> issues;

    public BuildValidationException(List<BuildIssue> issues) {
        super(formatIssues(issues));
        this.issues = List.copyOf(issues);
    }

    public List<BuildIssue> issues() {
        return issues;
    }

    private static String formatIssues(List<BuildIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "Build description validation failed";
        }
        return issues.stream()
                .map(issue -> issue.targetName() == null
                        ? issue.message()
                        : "target '" + issue.targetName() + "': " + issue.message())
                .collect(Collectors.joining("; ", "Build description validation failed: ", ""));
    }
}
