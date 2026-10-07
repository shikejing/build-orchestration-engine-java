package com.github.shikejing.buildorchestrationenginejava.engine.model;

import java.util.ArrayList;
import java.util.List;

/** Declarative description of a single build target. */
public class TargetDefinition {
    private String name;
    private String toolchain = "default";
    private List<String> deps = new ArrayList<>();
    private List<String> inputs = new ArrayList<>();
    private List<String> outputs = new ArrayList<>();
    private List<String> resources = new ArrayList<>();
    private ActionSpec action = new ActionSpec();
    private Long timeoutMs;
    private Integer retries;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getToolchain() { return toolchain; }
    public void setToolchain(String toolchain) { this.toolchain = toolchain; }
    public List<String> getDeps() { return deps; }
    public void setDeps(List<String> deps) { this.deps = deps; }
    public List<String> getInputs() { return inputs; }
    public void setInputs(List<String> inputs) { this.inputs = inputs; }
    public List<String> getOutputs() { return outputs; }
    public void setOutputs(List<String> outputs) { this.outputs = outputs; }
    public List<String> getResources() { return resources; }
    public void setResources(List<String> resources) { this.resources = resources; }
    public ActionSpec getAction() { return action; }
    public void setAction(ActionSpec action) { this.action = action; }
    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }
    public Integer getRetries() { return retries; }
    public void setRetries(Integer retries) { this.retries = retries; }
}
