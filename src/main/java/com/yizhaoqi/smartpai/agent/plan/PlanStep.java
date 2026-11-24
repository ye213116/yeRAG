package com.yizhaoqi.smartpai.agent.plan;

import java.util.*;

/** Server-owned step lifecycle; model notes are data, never proof of successful execution. */
public final class PlanStep {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, CANCELLED, INTERRUPTED }
    public enum Outcome { EVIDENCE_FOUND, NO_EVIDENCE, PARTIAL, FAILED, NOT_APPLICABLE }
    public record Result(Outcome outcome, String summary, List<Integer> sources, String reason) {
        public Result { sources = List.copyOf(sources); }
    }

    public int id;
    public String title;
    public Status status = Status.PENDING;
    public String message = "等待执行";
    public int attempts;
    public Result result;
    public Map<String, String> failures = new LinkedHashMap<>();
    public Set<Integer> evidence = new LinkedHashSet<>();
    public int successfulCalls;

    public PlanStep() {} // Durable checkpoint decoding.
    public PlanStep(int id, String title) { this.id = id; this.title = title; }

    public void start() {
        if (!Set.of(Status.PENDING, Status.INTERRUPTED).contains(status))
            throw new IllegalStateException("Step cannot start from " + status);
        status = Status.RUNNING;
        message = "正在执行";
        attempts++;
        failures.clear(); evidence.clear(); successfulCalls = 0; result = null;
    }

    public void finish(Result value) {
        if (status != Status.RUNNING) throw new IllegalStateException("Step is not running");
        result = value;
        status = value.outcome() == Outcome.FAILED || value.outcome() == Outcome.PARTIAL ? Status.FAILED : Status.COMPLETED;
        message = switch (value.outcome()) {
            case EVIDENCE_FOUND -> "已取得证据";
            case NO_EVIDENCE -> "未取得可引用证据";
            case PARTIAL -> "部分证据未取得：" + value.reason();
            case FAILED -> "任务未完成：" + value.reason();
            case NOT_APPLICABLE -> "回答已生成";
        };
    }

    public void interrupt(Status target, String reason) {
        if (status != Status.RUNNING) return;
        if (!Set.of(Status.INTERRUPTED, Status.CANCELLED, Status.FAILED).contains(target))
            throw new IllegalArgumentException("Invalid interruption");
        status = target; message = reason;
    }

    public Map<String, Object> display() {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", Integer.toString(id)); value.put("title", title);
        value.put("status", status.name().toLowerCase(Locale.ROOT)); value.put("message", message.substring(0, Math.min(200, message.length())));
        value.put("attempts", attempts);
        if (result != null) value.put("outcome", result.outcome().name());
        return value;
    }
}
