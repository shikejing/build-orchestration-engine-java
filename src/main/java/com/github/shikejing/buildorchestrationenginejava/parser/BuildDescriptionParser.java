package com.github.shikejing.buildorchestrationenginejava.parser;

import com.github.shikejing.buildorchestrationenginejava.model.BuildDescription;
import java.nio.file.Path;

public interface BuildDescriptionParser {
    BuildDescription parse(Path file);

    BuildDescription parseString(String content);
}
