package com.flj.rag;

/** A named, immutable embedding and chunking contract. Credentials are supplied separately. */
public record IndexConfig(String version, String embeddingProvider, String embeddingEndpoint, String embeddingModel,
                          int dimension, String collectionName, String extractorVersion, String chunkerVersion,
                          int chunkSize, int chunkOverlap) {
}
