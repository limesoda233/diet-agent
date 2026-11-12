package com.diet.service.meal.nutrition;

import com.diet.exception.DietException;
import com.diet.service.profile.DietaryProfileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class MealNutritionVariantService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NutritionTemplateService templates;
    private DietaryProfileService dietaryProfiles;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDietaryProfiles(DietaryProfileService profiles) { this.dietaryProfiles=profiles; }

    public MealNutritionVariantService(JdbcTemplate jdbc,ObjectMapper json,NutritionTemplateService templates) {
        this.jdbc=jdbc;this.json=json;this.templates=templates;
    }
    public NutritionTemplateService.Draft suggest(String name,String text,Map<String,String> suggested) {
        return templates.draft(name,text,suggested);
    }

    @Transactional
    public Variant savePersonal(long userId,long mealId,Input input) {
        if(input==null || input.label()==null || input.label().isBlank() || input.label().length()>64)
            throw new DietException("请为这份搭配填写简短名称");
        String mealName=visibleMealName(userId,mealId,true);
        if(!templates.templateFor(mealName).equals(input.templateCode()))
            throw new DietException("搭配模板与餐食类型不符");
        var selections=templates.validate(input.templateCode(),input.selections());
        if(dietaryProfiles!=null) {
            var profile=dietaryProfiles.get(userId);
            String spice=selections.get("spice");
            if("NEVER".equals(profile.spicePreference())&&!"NONE".equals(spice))
                throw new DietException("你设置了不吃辣，请调整这份搭配的辣度");
            if("MILD".equals(profile.spicePreference())&&("MEDIUM".equals(spice)||"HOT".equals(spice)))
                throw new DietException("你设置了只吃微辣，请调整这份搭配的辣度");
            boolean sesameRestricted=java.util.stream.Stream.concat(profile.allergens().stream(),profile.avoidedFoods().stream())
                    .anyMatch(value->value.contains("芝麻")||value.contains("麻酱"));
            if(sesameRestricted&&!"NONE".equals(selections.getOrDefault("sesame","NONE")))
                throw new DietException("这份搭配含麻酱，与你设置的过敏原或忌口冲突");
        }
        var estimate=templates.estimate(input.templateCode(),selections);
        String key=fingerprint(input.templateCode(),selections);
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO meal_nutrition_variant(meal_id,variant_key,label,template_code,selections,estimate_json,created_at,updated_at) "
                        +"VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE label=VALUES(label),estimate_json=VALUES(estimate_json),updated_at=VALUES(updated_at)",
                mealId,key,input.label().strip(),input.templateCode(),encode(selections),encode(estimate),now,now);
        return jdbc.queryForObject("SELECT id,meal_id,label,template_code,selections,estimate_json FROM meal_nutrition_variant WHERE meal_id=? AND variant_key=?",
                (row,index)->read(row.getLong(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5),row.getString(6)),mealId,key);
    }

    public List<Variant> list(long userId,long mealId) {
        visibleMealName(userId,mealId,false);
        return listInternal(mealId);
    }
    @Transactional
    public void deletePersonal(long userId,long mealId,long variantId) {
        visibleMealName(userId,mealId,true);
        if(jdbc.update("DELETE FROM meal_nutrition_variant WHERE id=? AND meal_id=?",variantId,mealId)==0)
            throw new DietException("餐食搭配版本不存在");
    }
    @Transactional
    public void clearWhenTemplateChanges(long userId,long mealId,String oldName,String newName) {
        if(templates.templateFor(oldName).equals(templates.templateFor(newName))) return;
        visibleMealName(userId,mealId,true);
        jdbc.update("DELETE FROM meal_nutrition_variant WHERE meal_id=?",mealId);
    }
    public List<Variant> listInternal(long mealId) {
        var stored=jdbc.query("SELECT id,meal_id,label,template_code,selections,estimate_json FROM meal_nutrition_variant WHERE meal_id=? ORDER BY id",
                (row,index)->read(row.getLong(1),row.getLong(2),row.getString(3),row.getString(4),row.getString(5),row.getString(6)),mealId);
        if(!stored.isEmpty()) return stored;
        var publicNames=jdbc.query("SELECT name FROM meal_item WHERE id=? AND source_type='PUBLIC' AND owner_user_id IS NULL",
                (row,index)->row.getString(1),mealId);
        if(publicNames.isEmpty())return List.of();
        var draft=templates.draft(publicNames.get(0),publicNames.get(0),Map.of());
        if(draft.estimate()==null)return List.of();
        return List.of(new Variant(0,mealId,"常见搭配参考",draft.templateCode(),draft.selections(),draft.estimate()));
    }
    public Variant bestInternal(long mealId,String goal) {
        var variants=listInternal(mealId);
        if(variants.isEmpty()) return null;
        var ordered=variants.stream().sorted((a,b)->{
            var x=a.estimate();var y=b.estimate();
            if(x==null||y==null)return x==y?0:x==null?1:-1;
            if("控碳水".equals(goal)||"低糖".equals(goal)) return Integer.compare(x.carbsHigh(),y.carbsHigh());
            if("减脂".equals(goal)) return Integer.compare(x.kcalHigh(),y.kcalHigh());
            if("高蛋白".equals(goal)||"增肌".equals(goal)) return Integer.compare(y.proteinLow(),x.proteinLow());
            return Long.compare(a.id(),b.id());
        }).toList();
        return ordered.get(0);
    }
    @Transactional
    public void movePersonal(long userId,long fromMealId,long toMealId) {
        visibleMealName(userId,fromMealId,true);
        visibleMealName(userId,toMealId,true);
        for(var variant:listInternal(fromMealId))
            savePersonal(userId,toMealId,new Input(variant.label(),variant.templateCode(),variant.selections()));
    }

    private String visibleMealName(long userId,long mealId,boolean personalOnly) {
        var names=jdbc.query("SELECT name FROM meal_item WHERE id=? AND ((source_type='PERSONAL' AND owner_user_id=?)"
                        +(personalOnly?"":" OR (source_type='PUBLIC' AND owner_user_id IS NULL)")+")",
                (row,index)->row.getString(1),mealId,userId);
        if(names.isEmpty())throw new DietException("餐食不存在或无权访问");
        return names.get(0);
    }
    private Variant read(long id,long mealId,String label,String template,String selections,String estimate) {
        try {
            return new Variant(id,mealId,label,template,json.readValue(selections,new TypeReference<Map<String,String>>(){}),
                    json.readValue(estimate,NutritionTemplateService.Estimate.class));
        } catch(JsonProcessingException error) {throw new DietException("餐食搭配数据读取失败");}
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException error){throw new DietException("餐食搭配数据无效");}
    }
    private String fingerprint(String template,Map<String,String> selections) {
        try {
            var digest=MessageDigest.getInstance("SHA-256").digest((template+"|"+new java.util.TreeMap<>(selections)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0,32);
        } catch(NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
    public record Input(String label,String templateCode,Map<String,String> selections) {
        public static Input from(Object raw) {
            if(!(raw instanceof Map<?,?> value) || !(value.get("label") instanceof String label)
                    || !(value.get("templateCode") instanceof String template)
                    || !(value.get("selections") instanceof Map<?,?> selected))
                throw new DietException("餐食搭配版本无效");
            var selections=new java.util.LinkedHashMap<String,String>();
            selected.forEach((key,choice)->{
                if(!(key instanceof String field)||!(choice instanceof String option))
                    throw new DietException("餐食搭配选项无效");
                selections.put(field,option);
            });
            return new Input(label,template,selections);
        }
    }
    public record Variant(long id,long mealId,String label,String templateCode,Map<String,String> selections,
                          NutritionTemplateService.Estimate estimate) {}
}
