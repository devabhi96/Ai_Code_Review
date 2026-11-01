package com.devabhi.aicodereview.review.validate;

import java.util.List;

import com.devabhi.aicodereview.review.model.DroppedFinding;
import com.devabhi.aicodereview.review.model.Finding;

public record ValidationResult(List<Finding> accepted, List<DroppedFinding> dropped) {
}