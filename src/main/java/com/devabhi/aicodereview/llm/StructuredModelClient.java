package com.devabhi.aicodereview.llm;

/**
 * The only provider-specific seam. Implementations throw LlmException on failure, with
 * messages that never contain prompt content.
 */
public interface StructuredModelClient {

    ModelReply complete(ModelRequest request);
}