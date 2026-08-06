package com.diet.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 多餐规划中的单个餐次分组。
 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class MealPlanBlock {
    /** 早餐、午餐或晚餐。 */
    private String mealTime;
    /** 该餐次的推荐说明。 */
    private String summary;
    /** 该餐次独立检索出的候选餐食。 */
    private List<MealResponse> meals;
}
