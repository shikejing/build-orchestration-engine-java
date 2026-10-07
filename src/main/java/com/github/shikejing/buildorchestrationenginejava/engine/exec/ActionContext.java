package com.github.shikejing.buildorchestrationenginejava.engine.exec;

import com.github.shikejing.buildorchestrationenginejava.engine.fingerprint.InputSource;
import com.github.shikejing.buildorchestrationenginejava.engine.model.TargetDefinition;

import java.nio.file.Path;

/** Everything an action needs at run time. */
public class ActionContext {
    private final TargetDefinition target;
    private final InputSource inputs;
    private final Path stagingDir;

    public ActionContext(TargetDefinition target, InputSource inputs, Path stagingDir) {
        this.target = target;
        this.inputs = inputs;
        this.stagingDir = stagingDir;
    }

    public TargetDefinition getTarget() { return target; }
    public InputSource getInputs() { return inputs; }
    public Path getStagingDir() { return stagingDir; }
}
