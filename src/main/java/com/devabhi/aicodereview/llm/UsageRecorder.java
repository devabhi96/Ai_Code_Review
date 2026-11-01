package com.devabhi.aicodereview.llm;

/** Receives usage after every model call. Persisting to the llm_call table comes with Step 8. */
public interface UsageRecorder {

    UsageRecorder NOOP = (stage, reply) -> { };

    void record(String stage, ModelReply reply);
}