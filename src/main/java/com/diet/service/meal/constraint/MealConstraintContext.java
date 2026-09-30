package com.diet.service.meal.constraint;

public record MealConstraintContext(String queryText,
                                    MealConstraints constraints,
                                    String source,
                                    String pendingConstraintText) {
    public MealConstraintContext(String queryText, MealConstraints constraints, String source) {
        this(queryText, constraints, source, "");
    }

    public MealConstraintContext {
        queryText = queryText == null ? "" : queryText;
        constraints = constraints == null ? MealConstraints.empty() : constraints;
        source = source == null ? "none" : source;
        pendingConstraintText = pendingConstraintText == null ? "" : pendingConstraintText;
        if (queryText.length() > 8000) {
            throw new IllegalArgumentException("会话餐食需求过长");
        }
    }

    public MealConstraintContext pending(String text) {
        return new MealConstraintContext(queryText, constraints, source, text);
    }

    public static MealConstraintContext empty() {
        return new MealConstraintContext("", MealConstraints.empty(), "none");
    }
}
