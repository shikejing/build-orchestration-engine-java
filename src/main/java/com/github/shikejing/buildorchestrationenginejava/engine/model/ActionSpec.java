package com.github.shikejing.buildorchestrationenginejava.engine.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Declarative description of a build action. */
public class ActionSpec {
    private ActionKind kind = ActionKind.WRITE;
    private Map<String, String> params = new LinkedHashMap<>();

    public ActionKind getKind() { return kind; }
    public void setKind(ActionKind kind) { this.kind = kind; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> params) { this.params = params; }
}
