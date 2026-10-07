package com.github.shikejing.buildorchestrationenginejava.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Root of the declarative project description. */
public class BuildSpec {
    private int version = 1;
    private Map<String, String> toolchains = new LinkedHashMap<>();
    private List<TargetDefinition> targets = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Map<String, String> getToolchains() { return toolchains; }
    public void setToolchains(Map<String, String> toolchains) { this.toolchains = toolchains; }
    public List<TargetDefinition> getTargets() { return targets; }
    public void setTargets(List<TargetDefinition> targets) { this.targets = targets; }
}
