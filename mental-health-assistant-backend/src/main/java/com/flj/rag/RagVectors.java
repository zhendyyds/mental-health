package com.flj.rag;

final class RagVectors {
    private RagVectors() { }

    static void requireValid(float[] vector, int dimension) {
        if (vector == null || vector.length != dimension || dimension < 1) throw invalid();
        boolean nonzero = false;
        for (float component : vector) {
            if (!Float.isFinite(component)) throw invalid();
            nonzero |= component != 0f;
        }
        if (!nonzero) throw invalid();
    }

    static RagIndexException invalid() {
        return new RagIndexException("502", "RAG_VECTOR_INVALID", "向量服务返回了无效向量", true);
    }
}
