package com.diet.service.memory;

import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.IngredientCatalog;
import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.recipe.rag.model.RecipeQuery;
import java.util.*;

/** Deterministic constraint policy, independent of the chat model or vector retrieval. */
public class MemoryPolicy {
    public static final String ALLERGY_LIMIT="你提供了过敏信息。当前餐食、菜谱和视频缺少完整配料及交叉接触证明，我无法核验是否适合你，因此暂不提供可供食用的推荐。请核实完整配料；长期过敏记录可在“我的记忆”确认或修改。";
    private final MemoryStore store;
    private final IngredientCatalog catalog=new IngredientCatalog();
    public MemoryPolicy(MemoryStore store) {this.store=store;}
    public List<MemoryItem> snapshot(long user) {return store.list(user);}
    public void checkAllergy(long user) {
        if(store.list(user).stream().anyMatch(i->"ALLERGEN".equals(i.type()))) throw new IllegalArgumentException(ALLERGY_LIMIT);
    }
    public SlotBundle defaults(long user,SlotBundle current) {
        return defaults(store.list(user),current);
    }
    public SlotBundle defaults(List<MemoryItem> items,SlotBundle current) {
        var c=current==null?SlotBundle.empty():current;
        List<String> healthDefaults=c.taste().isEmpty()?items.stream()
                .filter(i->"ACTIVE".equals(i.status()) && "EXPLICIT".equals(i.sourceType()) && "TASTE".equals(i.type()) && "LIKE".equals(i.stance()))
                .filter(i->catalog.supportsSlot("healthGoal",i.value())).map(MemoryItem::value).distinct().toList():List.of();
        return new SlotBundle(c.mealTime(),c.mood(),c.scene(),c.healthGoal().isEmpty()?healthDefaults:c.healthGoal(),
                c.cuisine().isEmpty()?preferences(items,"CUISINE"):c.cuisine(),
                c.taste().isEmpty()?preferences(items,"TASTE"):c.taste(),c.convenience());
    }
    private List<String> preferences(List<MemoryItem> items,String type) {
        // Only use values supported by the meal slot dictionary. Other facts remain visible in management.
        String field="TASTE".equals(type)?"taste":"cuisine";
        return items.stream().filter(i->"ACTIVE".equals(i.status()) && "EXPLICIT".equals(i.sourceType()) && type.equals(i.type()) && "LIKE".equals(i.stance()))
                .filter(i->catalog.supportsSlot(field,i.value())).map(MemoryItem::value).distinct().toList();
    }
    public MealConstraints mealConstraints(long user,MealConstraints current) {
        return mealConstraints(store.list(user),current);
    }
    public MealConstraints mealConstraints(List<MemoryItem> items,MealConstraints current) {
        if(items.stream().anyMatch(i->"ALLERGEN".equals(i.type()))) throw new IllegalArgumentException(ALLERGY_LIMIT);
        var base=current==null?MealConstraints.empty():current;
        var excluded=new LinkedHashSet<>(base.excludedIngredients());
        for(var i:items) if("ACTIVE".equals(i.status()) && "AVOID_INGREDIENT".equals(i.type())) excluded.add(catalog.normalize(i.value()));
        return new MealConstraints(base.requiredIngredients(),List.copyOf(excluded),base.requiredSlots(),base.excludedSlots());
    }
    public List<com.diet.model.MealItem> rank(long user,SlotBundle explicit,List<com.diet.model.MealItem> ranked) {
        return rank(store.list(user),explicit,ranked);
    }
    public List<com.diet.model.MealItem> rank(List<MemoryItem> items,SlotBundle explicit,List<com.diet.model.MealItem> ranked) {
        return ranked.stream().map(meal->{
            double adjustment=0;
            for(var item:items) {
                if(!"ACTIVE".equals(item.status()) || !Set.of("TASTE","CUISINE").contains(item.type()))continue;
                boolean taste="TASTE".equals(item.type());
                if(taste && (!explicit.taste().isEmpty() || !explicit.healthGoal().isEmpty()))continue;
                if(!taste && !explicit.cuisine().isEmpty())continue;
                var values=taste?new ArrayList<>(meal.slots().taste()):new ArrayList<>(meal.slots().cuisine());
                if(taste)values.addAll(meal.slots().healthGoal());
                if(values.contains(item.value())) adjustment+=("DISLIKE".equals(item.stance())?-1:"LIKE".equals(item.stance())?1:0)
                        *("INFERRED".equals(item.sourceType())?.02:.05);
            }
            return new com.diet.model.MealItem(meal.id(),meal.sourceType(),meal.ownerUserId(),meal.name(),meal.slots(),meal.matchScore()+adjustment,meal.knowledge());
        }).sorted(Comparator.comparingDouble((com.diet.model.MealItem meal)->meal.matchScore()).reversed()).toList();
    }
    public RecipeQuery recipeQuery(long user,RecipeQuery query) {
        checkAllergy(user);
        var items=store.list(user);
        var excluded=new LinkedHashSet<>(query.excludedIngredients());
        for(var i:items) if("ACTIVE".equals(i.status()) && "AVOID_INGREDIENT".equals(i.type())) excluded.add(i.value());
        if(query.requiredIngredients().stream().anyMatch(excluded::contains)) throw new IllegalArgumentException("本轮必须食材与长期忌口冲突，请先核对记忆");
        // Corpus has no verified duration/tool capability metadata. Keep recorded conditions visible,
        // but don't turn them into an unenforceable promise by attaching them to generated answers.
        return new RecipeQuery(query.text(),query.requiredIngredients(),List.copyOf(excluded),query.requiredTools(),query.maxMinutes());
    }
}
