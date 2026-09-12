package com.diet.service.cooking;
import java.util.Map;
/** Data-source boundary for validated recipe tool records. */
public interface RecipeProvider {
    Map<String,Object> call(String tool, Map<String,Object> arguments);
}
