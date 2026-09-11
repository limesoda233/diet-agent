package com.diet.service.retrieval;

/** Contains only a safe category/status, never an upstream response body or credentials. */
public class RetrievalDependencyException extends RuntimeException {
    public RetrievalDependencyException(String category) { super(category); }
}
