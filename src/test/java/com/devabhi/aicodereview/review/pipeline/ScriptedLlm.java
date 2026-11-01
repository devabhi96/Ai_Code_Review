package com.devabhi.aicodereview.review.pipeline;

import java.util.function.Function;

import com.devabhi.aicodereview.review.port.LlmPort;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;

/** Fake model: runs a script, remembers what it was given. */
class ScriptedLlm implements LlmPort {

    private final Function<SanitizedInput, LlmReview> script;
    int calls;
    SanitizedInput lastInput;

    ScriptedLlm(Function<SanitizedInput, LlmReview> script) {
        this.script = script;
    }

    @Override
    public LlmReview review(SanitizedInput input) {
        calls++;
        lastInput = input;
        return script.apply(input);
    }
}