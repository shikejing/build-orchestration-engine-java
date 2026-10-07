package com.github.shikejing.buildorchestrationenginejava.parser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.github.shikejing.buildorchestrationenginejava.error.BuildParseException;
import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import com.github.shikejing.buildorchestrationenginejava.model.TargetDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public class JsonBuildDescriptionParser implements BuildDescriptionParser {
    private final ObjectMapper mapper;

    public JsonBuildDescriptionParser() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.mapper.findAndRegisterModules();
    }

    @Override
    public BuildDescription parse(Path file) {
        if (file == null) {
            throw new BuildParseException("Description file must not be null");
        }
        try {
            DescriptionDto dto = mapper.readValue(Files.readAllBytes(file), DescriptionDto.class);
            return toModel(dto, "file " + file);
        } catch (IOException e) {
            throw new BuildParseException("Failed to read build description from " + file, e);
        }
    }

    @Override
    public BuildDescription parseString(String content) {
        if (content == null || content.isBlank()) {
            throw new BuildParseException("Build description content must not be empty");
        }
        try {
            DescriptionDto dto = mapper.readValue(content, DescriptionDto.class);
            return toModel(dto, "inline description");
        } catch (JsonProcessingException e) {
            throw new BuildParseException("Failed to parse build description: " + e.getOriginalMessage(), e);
        }
    }

    private BuildDescription toModel(DescriptionDto dto, String source) {
        if (dto == null) {
            throw new BuildParseException("Build description in " + source + " must be a JSON object");
        }
        if (dto.targets() == null) {
            throw new BuildParseException("Build description in " + source + " must contain 'targets'");
        }
        List<TargetDeclaration> targets;
        try {
            targets = dto.targets().stream()
                    .map(this::toTarget)
                    .toList();
        } catch (DateTimeParseException e) {
            throw new BuildParseException("Timeout must be an ISO-8601 duration, for example PT5S", e);
        }
        return new BuildDescription(dto.name(), targets);
    }

    private TargetDeclaration toTarget(TargetDto dto) {
        Duration timeout = dto.timeout() == null || dto.timeout().isBlank()
                ? null
                : Duration.parse(dto.timeout());
        return new TargetDeclaration(
                dto.name(),
                dto.dependsOn(),
                dto.inputs(),
                dto.outputs(),
                dto.toolchain(),
                dto.arguments(),
                dto.environment(),
                dto.exclusiveResources(),
                timeout,
                dto.retries()
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DescriptionDto(String name, List<TargetDto> targets) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TargetDto(
            String name,
            List<String> dependsOn,
            List<String> inputs,
            List<String> outputs,
            String toolchain,
            List<String> arguments,
            Map<String, String> environment,
            List<String> exclusiveResources,
            String timeout,
            Integer retries
    ) {
    }
}
