package com.flj.rag.qa;

import com.flj.AiService.governance.AiCallGovernor;
import com.flj.rag.RagEvidence;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory only. The byte budget covers retained content as well as the response. */
final class QaResultCache {
    private final int maxEntries;
    private final long maxBytes;
    private final long ttlNanos;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    QaResultCache(int maxEntries, long maxBytes, long ttlNanos) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.ttlNanos = ttlNanos;
    }

    synchronized Entry get(String requestId) {
        purgeExpired(System.nanoTime());
        return entries.get(requestId);
    }

    synchronized void put(QaResponse result, List<RagEvidence> evidence) {
        long now = System.nanoTime();
        purgeExpired(now);
        Entry previous = entries.remove(result.requestId());
        if (previous != null) { retainedBytes -= previous.bytes(); }
        long bytes = weight(result, evidence);
        if (bytes > maxBytes) { return; }

        entries.put(result.requestId(), new Entry(result, List.copyOf(evidence), bytes, now + ttlNanos));
        retainedBytes += bytes;
        Iterator<Entry> oldest = entries.values().iterator();
        while (entries.size() > maxEntries || retainedBytes > maxBytes) {
            retainedBytes -= oldest.next().bytes();
            oldest.remove();
        }
    }

    synchronized void clear() {
        entries.clear();
        retainedBytes = 0;
    }

    synchronized void purgeExpired() { purgeExpired(System.nanoTime()); }

    private void purgeExpired(long now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.expiresAtNanos() >= 0) {
                retainedBytes -= entry.bytes();
                iterator.remove();
            }
        }
    }

    private static long weight(QaResponse result, List<RagEvidence> evidence) {
        long bytes = 128 + size(result.requestId()) + size(result.answer());
        for (QaCitation citation : result.citations()) {
            bytes += 64 + size(citation.id()) + size(citation.articleId()) + size(citation.title()) + size(citation.excerpt());
        }
        for (RagEvidence source : evidence) {
            var key = source.candidate();
            bytes += 128 + size(source.title()) + size(source.section()) + size(source.content()) + size(source.contentHash())
                    + size(key.articleId()) + size(key.configVersion()) + size(key.generation()) + size(key.chunkId()) + size(key.vectorId());
        }
        return bytes;
    }

    private static long size(String value) { return AiCallGovernor.utf8Bytes(value); }

    record Entry(QaResponse response, List<RagEvidence> evidence, long bytes, long expiresAtNanos) { }
}
