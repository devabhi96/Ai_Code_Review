package com.devabhi.aicodereview.review.port;

import com.devabhi.aicodereview.review.secrets.SanitizedInput;

/**
 * The only way review logic talks to a model. The parameter type is SanitizedInput on purpose:
 * its constructor is package-private to the secrets package, so unredacted text cannot be
 * passed here. Implementations live outside the core (llm package); tests use fakes.
 */
public interface LlmPort {

    LlmReview review(SanitizedInput input);
}