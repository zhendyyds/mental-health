package com.flj.rag;

/** Identity received from a vector search. The vector payload text is never authoritative. */
public record IndexCandidate(String articleId, long revision, String configVersion, String generation,
                             String chunkId, String vectorId) {
}
