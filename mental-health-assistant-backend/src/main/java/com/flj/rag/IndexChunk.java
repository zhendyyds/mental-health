package com.flj.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record IndexChunk(String chunkId, int chunkIndex, String section, String content, String contentHash,
                         String vectorId) {
    public static IndexChunk create(IndexJob job, int chunkIndex, String section, String content) {
        String key = String.join("\0", "knowledge-chunk-v1", job.articleId(), Long.toString(job.revision()),
                job.configVersion(), job.generation(), Integer.toString(chunkIndex));
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new IndexChunk(id, chunkIndex, section, content, hashContent(content), id);
    }

    public static String hashContent(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
