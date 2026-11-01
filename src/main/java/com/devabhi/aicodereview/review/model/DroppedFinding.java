package com.devabhi.aicodereview.review.model;

/** A finding that failed validation, kept with the reason so quality problems stay measurable. */
public record DroppedFinding(RawFinding raw, DropReason reason, String detail) {
}