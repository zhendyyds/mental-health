package com.flj.rag;

public record ValidatedChunk(IndexCandidate candidate, String title, String section, String content,
                             String contentHash) {
}
