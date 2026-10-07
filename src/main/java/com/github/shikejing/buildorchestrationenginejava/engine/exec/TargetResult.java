package com.github.shikejing.buildorchestrationenginejava.engine.exec;

/** Per-target outcome: status, duration and decision reason. */
public class TargetResult {
    private final String name;
    private volatile TargetStatus status = TargetStatus.CANCELLED;
    private volatile DecisionReason reason = DecisionReason.CANCELLED_BY_FAILURE;
    private volatile long durationMs = -1;
    private volatile String detail = "";

    public TargetResult(String name) { this.name = name; }

    public String getName() { return name; }
    public TargetStatus getStatus() { return status; }
    public void setStatus(TargetStatus status) { this.status = status; }
    public DecisionReason getReason() { return reason; }
    public void setReason(DecisionReason reason) { this.reason = reason; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
