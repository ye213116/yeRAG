package com.yizhaoqi.smartpai.agent.plan;

import com.yizhaoqi.smartpai.agent.*;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;

/** One attempt's journal. The database fences every write by attempt, version and live lease. */
public final class PlanJournal {
    public static final String CONTEXT_KEY = PlanJournal.class.getName();
    private final PlanStore store;
    private final Turn turn;
    private final SourceMode mode;
    private PlanStore.Execution execution;
    private boolean live = true;

    public PlanJournal(PlanStore store, Turn turn, SourceMode mode) {
        this.store = store; this.turn = turn; this.mode = mode;
    }
    public PlanJournal(PlanStore store, Turn turn, PlanStore.Execution execution) {
        this(store, turn, execution.mode()); this.execution = execution;
    }
    public synchronized boolean created() { return execution != null; }
    public synchronized PlanStore.Execution execution() { return execution; }
    public synchronized void open(PlanCheckpoint checkpoint) {
        if (!live || execution != null) throw new LeaseLost();
        execution = store.create(turn, mode, checkpoint);
    }
    public synchronized void checkpoint(PlanCheckpoint checkpoint, String phase) {
        requireCurrent(); execution = store.checkpoint(execution, checkpoint, phase);
    }
    public synchronized void budget(int calls, int memory, int artifacts, int decisions, boolean unsafe) {
        if (execution == null) return;
        requireCurrent(); execution = store.budget(execution, calls, memory, artifacts, decisions, unsafe);
    }
    public synchronized void requireCurrent() {
        if (!live || execution != null && !store.current(execution)) { live = false; throw new LeaseLost(); }
    }
    public synchronized void heartbeat() {
        if (execution != null && live && !store.heartbeat(execution)) { live = false; throw new LeaseLost(); }
    }
    public synchronized boolean complete(AgentRunResult result, MemoryStore memory) {
        if (execution == null) return memory.complete(turn, result.answer());
        requireCurrent(); boolean saved = store.complete(execution, turn, result.answer()); live = false; return saved;
    }
    public synchronized void stop(boolean cancelled, String reason, boolean resumable) {
        try { if (execution != null) store.stop(execution, cancelled, reason, resumable); }
        finally { live = false; }
    }
    public static final class LeaseLost extends SecurityException {
        public LeaseLost() { super("Plan attempt is no longer current"); }
    }
}
