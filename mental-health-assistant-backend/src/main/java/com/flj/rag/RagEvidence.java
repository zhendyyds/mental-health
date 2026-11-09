package com.flj.rag;

/** Text and citation fields come from MySQL. Qdrant contributes only rank and identity. */
public record RagEvidence(IndexCandidate candidate, String title, String section,
                          String content, String contentHash, double score) { }
