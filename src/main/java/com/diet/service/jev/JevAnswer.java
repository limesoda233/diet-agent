package com.diet.service.jev;

import java.util.Map;

/** One typed answer returned by Jev. Only fields relevant to its type are populated. */
public record JevAnswer(
        String type,
        String choice,
        Double score,
        Double noul,
        Double confidence,
        Map<String, Double> probabilities
) {
}
