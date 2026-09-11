package com.diet.service.retrieval;

public record RetrievalContext(String queryText, MealConstraints constraints, String source, String pendingConstraintText) {
    public RetrievalContext(String queryText, MealConstraints constraints, String source) {
        this(queryText,constraints,source,"");
    }
    public RetrievalContext {
        queryText = queryText == null ? "" : queryText;
        constraints = constraints == null ? MealConstraints.empty() : constraints;
        source = source == null ? "none" : source;
        pendingConstraintText = pendingConstraintText == null ? "" : pendingConstraintText;
        if (queryText.length() > 8000) throw new IllegalArgumentException("会话检索需求过长");
    }
    public RetrievalContext pending(String text) { return new RetrievalContext(queryText,constraints,source,text); }
    public static RetrievalContext empty() { return new RetrievalContext("", MealConstraints.empty(), "none"); }
}
