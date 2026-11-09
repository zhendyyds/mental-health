package com.flj.rag;

import java.util.List;

public interface RagVectorGateway {
    void upsert(IndexConfig config, List<RagVectorPoint> points);

    List<RagVectorMatch> search(IndexConfig config, float[] vector, int topK, double scoreThreshold);

    void deleteGeneration(IndexConfig config, String articleId, long revision, String generation);
}
