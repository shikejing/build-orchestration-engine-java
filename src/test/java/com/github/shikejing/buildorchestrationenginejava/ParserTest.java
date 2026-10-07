package com.github.shikejing.buildorchestrationenginejava;

import com.github.shikejing.buildorchestrationenginejava.error.BuildParseException;
import com.github.shikejing.buildorchestrationenginejava.parser.JsonBuildDescriptionParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {
    private final JsonBuildDescriptionParser parser = new JsonBuildDescriptionParser();

    @Test
    void rejectsMalformedJson() {
        BuildParseException error = assertThrows(BuildParseException.class,
                () -> parser.parseString("{ not json"));
        assertTrue(error.getMessage().contains("Failed to parse"));
    }

    @Test
    void rejectsUnknownFieldsToAvoidLoadingHalfUnderstoodDescription() {
        assertThrows(BuildParseException.class, () -> parser.parseString("""
                {
                  "name": "x",
                  "targets": [
                    {
                      "name": "a",
                      "toolchain": "write-text",
                      "outputs": ["a.txt"],
                      "unknown": true
                    }
                  ]
                }
                """));
    }
}
