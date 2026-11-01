package com.devabhi.aicodereview.review.secrets;

import com.devabhi.aicodereview.review.diff.ParsedPatch;

public record SanitizedFile(String path, ParsedPatch patch, String context) {
}