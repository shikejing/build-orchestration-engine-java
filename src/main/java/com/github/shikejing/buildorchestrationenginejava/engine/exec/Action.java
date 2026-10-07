package com.github.shikejing.buildorchestrationenginejava.engine.exec;

/** A runnable build action; writes outputs into the staging dir only. */
public interface Action {
    void run(ActionContext ctx) throws Exception;
}
