package com.devabhi.aicodereview.review.pipeline;

import java.util.List;
import java.util.Map;

import com.devabhi.aicodereview.review.filter.ChangedFile;

/** Everything the engine needs for one review. fileContents maps path to full file text (optional). */
public record ReviewRequest(String title, String description, List<ChangedFile> files,
                            Map<String, String> fileContents, List<String> ignoreGlobs,
                            boolean llmEnabled) {

    public ReviewRequest {
        files = files == null ? List.of() : List.copyOf(files);
        fileContents = fileContents == null ? Map.of() : Map.copyOf(fileContents);
        ignoreGlobs = ignoreGlobs == null ? List.of() : List.copyOf(ignoreGlobs);
    }

    public static ReviewRequest of(String title, String description, List<ChangedFile> files) {
        return new ReviewRequest(title, description, files, Map.of(), List.of(), true);
    }

    String contextOf(String path) {
        return fileContents.get(path);
    }
}