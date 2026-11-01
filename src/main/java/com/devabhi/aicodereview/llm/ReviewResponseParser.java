package com.devabhi.aicodereview.llm;

import java.util.List;

import com.devabhi.aicodereview.review.model.RawFinding;
import com.devabhi.aicodereview.review.model.SuggestedFix;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the tool arguments JSON into untrusted RawFindings. It only checks shape; meaning is
 * checked later by FindingValidator. Error messages never include model output.
 */
public final class ReviewResponseParser {

    /** Hard cap so a runaway answer cannot flood the validator. */
    static final int MAX_FINDINGS = 50;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FixDto(String description, @JsonProperty("replacement_code") String replacementCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FindingDto(String file, Integer line, @JsonProperty("end_line") Integer endLine,
                      String side, String category, String severity, Double confidence,
                      String cwe, String title, String explanation, String evidence,
                      @JsonProperty("suggested_fix") FixDto suggestedFix) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseDto(String summary, List<FindingDto> findings) {
    }

    public LlmReview parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidModelOutputException("empty model output");
        }
        ResponseDto dto;
        try {
            dto = mapper.readValue(json, ResponseDto.class);
        } catch (RuntimeException e) {
            throw new InvalidModelOutputException(
                    "model output is not valid JSON for the report_findings schema");
        }
        if (dto == null || dto.findings() == null) {
            throw new InvalidModelOutputException("model output has no findings array");
        }
        List<RawFinding> findings = dto.findings().stream()
                .limit(MAX_FINDINGS)
                .map(ReviewResponseParser::toRaw)
                .toList(); // null entries stay null; FindingValidator drops them as MALFORMED
        return new LlmReview(dto.summary(), findings);
    }

    private static RawFinding toRaw(FindingDto d) {
        if (d == null) {
            return null;
        }
        SuggestedFix fix = d.suggestedFix() == null ? null
                : new SuggestedFix(d.suggestedFix().description(), d.suggestedFix().replacementCode());
        return new RawFinding(d.file(), d.line(), d.endLine(), d.side(), d.category(), d.severity(),
                d.confidence(), d.cwe(), d.title(), d.explanation(), d.evidence(), fix);
    }
}