package com.devabhi.aicodereview.review.diff;

import java.util.List;

public record Hunk(int oldStart, int oldCount, int newStart, int newCount,
                   String sectionHeading, List<DiffLine> lines) {
}