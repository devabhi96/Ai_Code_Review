package com.devabhi.aicodereview.review.filter;

import java.util.List;

public record FilterResult(List<ChangedFile> reviewable, List<SkippedFile> skipped) {
}