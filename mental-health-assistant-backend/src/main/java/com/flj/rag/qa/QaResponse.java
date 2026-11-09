package com.flj.rag.qa;

import java.util.List;

public record QaResponse(String requestId, String answer, boolean grounded, List<QaCitation> citations) {
    public QaResponse {
        citations = List.copyOf(citations);
    }
}
