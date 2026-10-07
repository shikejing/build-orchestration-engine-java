package com.github.shikejing.buildorchestrationenginejava.engine.spec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.buildorchestrationenginejava.engine.model.BuildSpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Parses a declarative build spec (JSON) from disk or a string. */
public class BuildSpecParser {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BuildSpec parse(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    public BuildSpec parse(String json) {
        try {
            BuildSpec spec = mapper.readValue(json, BuildSpec.class);
            if (spec == null) {
                throw new SpecException(List.of("spec: empty document"));
            }
            return spec;
        } catch (SpecException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecException(List.of("spec: malformed document: " + e.getMessage()));
        }
    }

    public BuildSpec parseUnchecked(Path file) {
        try {
            return parse(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
