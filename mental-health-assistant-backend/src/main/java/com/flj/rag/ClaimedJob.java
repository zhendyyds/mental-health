package com.flj.rag;

/** Snapshot copied while claiming; the worker can use it outside the database transaction. */
public record ClaimedJob(IndexJob job, IndexConfig config, String title, String content) {
}
