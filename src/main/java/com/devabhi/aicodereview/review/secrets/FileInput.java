package com.devabhi.aicodereview.review.secrets;

import com.devabhi.aicodereview.review.diff.ParsedPatch;

/** Raw, unredacted input for one file. context is the full file at the head commit (may be null). */
public record FileInput(String path, ParsedPatch patch, String context) {
}