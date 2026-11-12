package com.diet.service.meal.nutrition;

import com.diet.service.meal.intake.MealDraftModel;
import com.diet.service.profile.DietaryProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NutritionSuggestionService {
    private final MealDraftModel model;
    private final ObjectMapper json;
    private final NutritionTemplateService templates;
    private final DietaryProfileService profiles;
    public NutritionSuggestionService(MealDraftModel model,ObjectMapper json,NutritionTemplateService templates,
                                      DietaryProfileService profiles) {
        this.model=model;this.json=json;this.templates=templates;this.profiles=profiles;
    }
    public NutritionTemplateService.Draft suggest(long userId,String name,String text) {
        String template=templates.templateFor(name);
        if("UNKNOWN".equals(template)) return templates.draft(name,text,Map.of());
        var choices=new LinkedHashMap<String,String>();
        String restrictions="";
        if(profiles!=null) {
            var profile=profiles.get(userId);
            if("NEVER".equals(profile.spicePreference())) restrictions+="；不吃辣";
            if(profile.allergens().stream().anyMatch(value->value.contains("芝麻")||value.contains("麻酱"))
                    ||profile.avoidedFoods().stream().anyMatch(value->value.contains("芝麻")||value.contains("麻酱")))
                restrictions+="；不加麻酱";
        }
        try {
            String prompt="你是餐食搭配选项提取器。只输出一个JSON对象，键必须来自提供的选项，值必须是对应的选项代码。"
                    +"只根据用户明确描述预选；不要猜克数、热量、食材或添加额外问题。用户的否定条件优先。"
                    +json.writeValueAsString(templates.questions(template));
            String result=model.extract(prompt,json.writeValueAsString(Map.of("name",name,"text",text==null?"":text)))
                    .timeout(Duration.ofSeconds(8)).block();
            JsonNode root=json.readTree(result);
            if(root!=null&&root.isObject()) root.fields().forEachRemaining(field->{
                if(field.getValue().isTextual()) choices.put(field.getKey(),field.getValue().textValue());
            });
        } catch(Exception ignored) { /* The finite questionnaire still works when AI is unavailable. */ }
        return templates.draft(name,(text==null?"":text)+restrictions,choices);
    }
}
