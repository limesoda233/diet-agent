package com.diet.service.retrieval;

import java.util.List;

public interface EmbeddingClient {
    boolean configured();
    List<float[]> embedDocuments(List<String> documents);
    float[] embedQuery(String query);
    default int lastRetryCount() { return 0; }
}
