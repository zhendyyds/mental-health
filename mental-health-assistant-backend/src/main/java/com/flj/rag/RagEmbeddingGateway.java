package com.flj.rag;

import java.util.List;

public interface RagEmbeddingGateway {
    List<float[]> embed(IndexConfig config, List<String> texts);
}
