package com.diet.service.meal.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("diet.meal-intake")
public class MealIntakeProperties {
    private Duration timeout = Duration.ofSeconds(10);
    private int maxInputChars = 4000;
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("餐食提取超时必须为正");
        timeout = value;
    }
    public int getMaxInputChars() { return maxInputChars; }
    public void setMaxInputChars(int value) {
        if (value < 1 || value > 4000) throw new IllegalArgumentException("餐食输入上限必须在1到4000之间");
        maxInputChars = value;
    }
}
