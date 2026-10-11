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

    // 库存语境允许省略“菜”：如“冰箱里剩了点鸡腿肉，可以做什么”。
    // 同时要求存放/剩余动词与完整的烹饪疑问结尾，避免把冰箱故障、工作能力问法当菜谱。
    private static final Pattern FRIDGE_INVENTORY_QUESTION = Pattern.compile(
            "冰箱(?:里(?:面)?|内|中)?[，,\\s]*"
                    + "(?:(?:还|只)?有|(?:还|只)?剩(?:下|了)?|放(?:着|了)|存(?:着|了))"
                    + "[^。！？!?]{1,100}(?:能|可以|可|适合)(?:用来)?"
                    + "做(?:点|些)?(?:什么|哪些|哪几|啥)(?=[，,。！？!?\\s]|$)");

    public static RecipeAnswerPurpose fromText(String text) {
        return text != null && (INGREDIENT_QUESTION.matcher(text).find()
                || FRIDGE_INVENTORY_QUESTION.matcher(text).find()) ? INGREDIENT_SUGGESTION : RECIPE;
    }
}
