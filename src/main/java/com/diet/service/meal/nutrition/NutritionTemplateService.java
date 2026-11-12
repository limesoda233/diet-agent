package com.diet.service.meal.nutrition;

import com.diet.exception.DietException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Finite, reviewable choices and deliberately broad portion estimates for variable takeaway meals. */
@Service
public class NutritionTemplateService {
    private static final List<Question> MALATANG = List.of(
            q("size", "这一份有多大？", "SMALL", "小份", "NORMAL", "常规份", "LARGE", "大份"),
            q("composition", "蔬菜和肉/豆制品怎么搭配？", "VEG_HEAVY", "蔬菜多", "BALANCED", "差不多", "PROTEIN_HEAVY", "肉/豆制品多"),
            q("starch", "粉、面、土豆等主食有多少？", "NONE", "不加", "LITTLE", "少量", "REGULAR", "常规", "EXTRA", "较多"),
            q("oil", "汤底和用油？", "LIGHT", "清淡", "NORMAL", "正常", "HEAVY", "重油"),
            q("sesame", "麻酱？", "NONE", "不加", "SMALL", "少量", "NORMAL", "常规"),
            q("sugar", "白糖或甜口调味？", "NONE", "不加", "SMALL", "少量", "NORMAL", "常规"),
            q("spice", "辣度？", "NONE", "不辣", "MILD", "微辣", "MEDIUM", "中辣", "HOT", "重辣"));
    private static final List<Question> SKEWERS = List.of(
            q("size", "吃了多少？", "SMALL", "约 3 串", "NORMAL", "约 6 串", "LARGE", "约 10 串"),
            q("composition", "串的组成？", "VEG_HEAVY", "蔬菜多", "BALANCED", "差不多", "PROTEIN_HEAVY", "肉/豆制品多"),
            q("starch", "年糕、馒头等主食？", "NONE", "不加", "LITTLE", "少量", "REGULAR", "常规", "EXTRA", "较多"),
            q("oil", "沥油情况？", "LIGHT", "沥油较多", "NORMAL", "正常", "HEAVY", "偏油"),
            q("sugar", "甜口酱料？", "NONE", "不加", "SMALL", "少量", "NORMAL", "常规"),
            q("spice", "辣度？", "NONE", "不辣", "MILD", "微辣", "MEDIUM", "中辣", "HOT", "重辣"));
    private static final Map<String,String> DEFAULTS = Map.of("size","NORMAL", "composition","BALANCED",
            "starch","LITTLE", "oil","NORMAL", "sesame","NONE", "sugar","NONE", "spice","MILD");
    private static final Pattern NO_SPICE = Pattern.compile("不加辣|不要辣|不放辣|不吃辣|不辣|无辣|免辣|去辣");
    private static final Pattern NO_SESAME = Pattern.compile("不加麻酱|不要麻酱|不放麻酱|无麻酱|去麻酱");
    private static final Pattern NO_SUGAR = Pattern.compile("不加糖|不要糖|不放糖|无糖|去糖|不加白糖");

    private static Question q(String key,String label,String... values) {
        var options=new ArrayList<Option>();
        for(int i=0;i<values.length;i+=2) options.add(new Option(values[i],values[i+1]));
        return new Question(key,label,List.copyOf(options));
    }

    public Draft draft(String name,String text,Map<String,String> suggested) {
        String template=templateFor(name);
        List<Question> questions=questions(template);
        if (questions.isEmpty()) return new Draft("UNKNOWN",List.of(),Map.of(),null);
        var selected=new LinkedHashMap<String,String>();
        for (Question question:questions) {
            String candidate=suggested==null?null:suggested.get(question.key());
            String proposed=candidate;
            if (question.options().stream().noneMatch(choice->choice.value().equals(proposed)))
                candidate=DEFAULTS.get(question.key());
            selected.put(question.key(),candidate);
        }
        String raw=text==null?"":text;
        if(NO_SPICE.matcher(raw).find()) selected.put("spice","NONE");
        if(NO_SESAME.matcher(raw).find()&&selected.containsKey("sesame")) selected.put("sesame","NONE");
        if(NO_SUGAR.matcher(raw).find()) selected.put("sugar","NONE");
        if(raw.contains("少油")||raw.contains("清汤")) selected.put("oil","LIGHT");
        if(raw.contains("重油")||raw.contains("多油")) selected.put("oil","HEAVY");
        if(raw.contains("不要主食")||raw.contains("不加主食")||raw.contains("不加面")||raw.contains("不加粉")) selected.put("starch","NONE");
        return new Draft(template,questions,Map.copyOf(selected),estimate(template,selected));
    }

    public String templateFor(String name) {
        String value=name==null?"":name.strip();
        if(value.contains("麻辣烫")||value.contains("冒菜")) return "MALATANG";
        if(value.contains("炸串")||value.contains("串串")) return "SKEWERS";
        return "UNKNOWN";
    }
    public List<Question> questions(String template) {
        return switch(template) { case "MALATANG" -> MALATANG; case "SKEWERS" -> SKEWERS; default -> List.of(); };
    }
    public Map<String,String> validate(String template,Map<String,String> selections) {
        List<Question> questions=questions(template);
        if(questions.isEmpty()||selections==null||selections.size()!=questions.size()) throw new DietException("餐食搭配选项不完整");
        var safe=new LinkedHashMap<String,String>();
        for(Question question:questions) {
            String value=selections.get(question.key());
            if(question.options().stream().noneMatch(option->option.value().equals(value)))
                throw new DietException("餐食搭配选项无效："+question.label());
            safe.put(question.key(),value);
        }
        return Map.copyOf(safe);
    }

    public Estimate estimate(String template,Map<String,String> selections) {
        if(questions(template).isEmpty()) return null;
        var values=validate(template,selections);
        double grams=switch(values.get("size")) {
            case "SMALL" -> "SKEWERS".equals(template)?180:350;
            case "LARGE" -> "SKEWERS".equals(template)?600:700;
            default -> "SKEWERS".equals(template)?360:500;
        };
        double starchShare=switch(values.get("starch")) {case "NONE"->0;case "LITTLE"->0.12;case "EXTRA"->0.40;default->0.25;};
        double vegRatio=switch(values.get("composition")) {case "VEG_HEAVY"->0.7;case "PROTEIN_HEAVY"->0.35;default->0.55;};
        double veg=grams*(1-starchShare)*vegRatio;
        double proteinFood=grams*(1-starchShare)*(1-vegRatio);
        double starch=grams*starchShare;
        double oil=switch(values.get("oil")) {case "LIGHT"->5;case "HEAVY"->30;default->15;};
        if("SKEWERS".equals(template)) oil*=1.4;
        double sesame=switch(values.getOrDefault("sesame","NONE")) {case "SMALL"->10;case "NORMAL"->20;default->0;};
        double sugar=switch(values.get("sugar")) {case "SMALL"->5;case "NORMAL"->12;default->0;};
        // Broad category densities, not measured nutrient facts for this particular meal.
        double kcal=veg*0.3+proteinFood*1.8+starch*1.3+oil*9+sesame*6+sugar*4;
        double carbs=veg*0.05+proteinFood*0.03+starch*0.28+sesame*0.1+sugar;
        double protein=veg*0.02+proteinFood*0.20+starch*0.03+sesame*0.2;
        double fat=veg*0.002+proteinFood*0.10+starch*0.005+oil+sesame*0.53;
        double fiber=veg*0.02+starch*0.01+sesame*0.05;
        return new Estimate(low(kcal,0.70),high(kcal,1.30),low(carbs,0.7),high(carbs,1.3),
                low(protein,0.7),high(protein,1.3),low(fat,0.7),high(fat,1.3),
                low(fiber,0.7),high(fiber,1.3),
                "APPROX_RULE_V1",List.of("按所选份量与组成档位估算", "不同食材、烹调及商家做法会改变实际数值"));
    }
    private int low(double value,double factor) { return Math.max(0,(int)Math.floor(value*factor)); }
    private int high(double value,double factor) { return Math.max(1,(int)Math.ceil(value*factor)); }

    public record Option(String value,String label) {}
    public record Question(String key,String label,List<Option> options) {}
    public record Estimate(int kcalLow,int kcalHigh,int carbsLow,int carbsHigh,int proteinLow,int proteinHigh,
                           int fatLow,int fatHigh,int fiberLow,int fiberHigh,String method,List<String> assumptions) {}
    public record Draft(String templateCode,List<Question> questions,Map<String,String> selections,Estimate estimate) {}
}
