package com.diet.service.recipe.rag.model;

import java.util.regex.Pattern;

/** 回答目的来自完整原问题，不使用检索改写文本推断用户的库存或限制。 */
public enum RecipeAnswerPurpose {
    RECIPE,
    INGREDIENT_SUGGESTION;

    private static final Pattern INGREDIENT_QUESTION = Pattern.compile(
            "(?:能|可以|可|适合)(?:用来)?做(?:点|些)?(?:什么|哪些|哪几|啥)(?:菜|饭|料理)"
                    + "|食材[，,\\s]*(?:(?:还|都|只)?(?:能|可以|可|适合))?(?:用来)?"
                    + "(?:做(?:点|些)?(?:什么|哪些|啥)|推荐(?:什么|哪些|哪几)?菜)"
                    + "|(?:用|有).{1,40}(?:做什么菜|做哪些菜|做啥菜)");

    public static RecipeAnswerPurpose fromText(String text) {
        return text != null && INGREDIENT_QUESTION.matcher(text).find() ? INGREDIENT_SUGGESTION : RECIPE;
    }
}
