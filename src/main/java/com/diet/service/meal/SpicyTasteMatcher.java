package com.diet.service.meal;

import java.util.ArrayList;
import java.util.List;

/** "辣" is a broad request; intensity-specific and "不辣" tags keep their exact meaning. */
final class SpicyTasteMatcher {
    private static final List<String> SPICY = List.of("辣", "微辣", "中辣", "重辣", "麻辣");

    private SpicyTasteMatcher() { }

    static List<String> expandForSearch(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        var expanded = new ArrayList<>(requested);
        if (requested.contains("辣"))
            for (String taste : SPICY) if (!expanded.contains(taste)) expanded.add(taste);
        return expanded;
    }

    static boolean matches(String requested, List<String> available) {
        if (available == null) return false;
        if ("辣".equals(requested)) return available.stream().anyMatch(SPICY::contains);
        return available.contains(requested);
    }
}
