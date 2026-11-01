package com.devabhi.aicodereview.review.model;

public enum DropReason {
    MALFORMED,
    INVALID_ENUM,
    INVALID_CONFIDENCE,
    INVALID_SIDE,
    FILE_NOT_IN_PR,
    LINE_NOT_IN_DIFF,
    INVALID_RANGE,
    EVIDENCE_TOO_SHORT,
    EVIDENCE_MISMATCH,
    EVIDENCE_AMBIGUOUS
}