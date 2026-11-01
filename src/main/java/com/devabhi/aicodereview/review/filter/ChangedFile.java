package com.devabhi.aicodereview.review.filter;

/** One file of a PR as GitHub reports it. filename is always the NEW path. */
public record ChangedFile(String filename, String status, String previousFilename, String patch) {
}