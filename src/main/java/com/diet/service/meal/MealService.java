package com.diet.service.meal;

import com.diet.exception.DietException;
import com.diet.exception.DuplicateMealException;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItem;
import com.diet.model.MealItemRow;
import com.diet.model.MealRequest;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.profile.DietaryProfileService;
import com.diet.util.JsonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * 餐食数据服务。
 * 提供 CRUD 和基于 MySQL JSON_OVERLAPS 的标签检索；Orchestrator 推荐链路通过 {@link #search} 召回候选。
 */
@Service
public class MealService {
    private JdbcTemplate guardJdbc;
    @Autowired(required=false)
    public void setGuardJdbc(JdbcTemplate jdbc) { this.guardJdbc=jdbc; }
    private DietaryProfileService dietaryProfiles;
    @Autowired(required=false)
    public void setDietaryProfileService(DietaryProfileService profiles) { this.dietaryProfiles=profiles; }
    private com.diet.service.memory.MemoryPolicy memoryPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMemoryPolicy(com.diet.service.memory.MemoryPolicy policy) {this.memoryPolicy=policy;}

    /** 单次检索从 DB 拉取的最大行数，初排后取 top10 交给 Rank 层。 */
    private static final int SEARCH_LIMIT = 50;
    private final MealMapper mealMapper;
    private final SlotOptionService slotOptionService;
    private final JsonService jsonService;
    private final MealKnowledgeRepository knowledgeRepository;
    public MealService(MealMapper mealMapper, SlotOptionService slotOptionService, JsonService jsonService) {
        this(mealMapper, slotOptionService, jsonService, null);
    }
    @Autowired
    public MealService(MealMapper mealMapper, SlotOptionService slotOptionService, JsonService jsonService,
                       MealKnowledgeRepository knowledgeRepository) {
        this.mealMapper = mealMapper;
        this.slotOptionService = slotOptionService;
        this.jsonService = jsonService;
        this.knowledgeRepository = knowledgeRepository;
    }

    public List<MealItem> findPersonalMeals(Long userId) {
        return attachKnowledge(mealMapper.findPersonalMeals(userId).stream().map(this::toMealItem).toList());
    }

    public List<MealItem> findPublicMeals() {
        return attachKnowledge(mealMapper.findPublicMeals().stream().map(this::toMealItem).toList());
    }

    /**
     * PERSONAL 模式空库前置检查。
     * 由 Orchestrator#handleTurn 调用，count > 0 才继续推荐链路。
     */
    public boolean hasPersonalMeals(Long userId) {
        return mealMapper.countPersonalMeals(userId) > 0; // 查个人餐食数量是否大于 0
    }

    @Transactional
    public MealItem createPersonalMeal(Long userId, MealRequest request) {
        validateMealRequest(request);
        validateDietaryRestrictions(userId,request);
        claimMealName(userId,request.name(),false);
        if (!sameNamedPersonal(userId,request.name()).isEmpty()) throw new DuplicateMealException();
        return insertPersonalMeal(userId,request);
    }

    private MealItem insertPersonalMeal(Long userId,MealRequest request) {
        MealItemRow row = toRow(null, SourceMode.PERSONAL, userId, request);
        mealMapper.insert(row);
        saveKnowledge(row.getId(), request);
        return attachKnowledge(List.of(toMealItem(row))).get(0);
    }

    @Transactional
    public MealItem mergePersonalMeal(Long userId,MealRequest request) {
        validateMealRequest(request);
        validateDietaryRestrictions(userId,request);
        claimMealName(userId,request.name(),true);
        var matches=sameNamedPersonal(userId,request.name());
        if (matches.isEmpty()) return insertPersonalMeal(userId,request);
        matches=matches.stream().sorted(Comparator.comparing(MealItem::id)).toList();
        var primary=matches.get(0);
        var all=new ArrayList<>(matches);
        var incoming=new MealItem(null,SourceMode.PERSONAL,userId,request.name(),request.toSlots(),0);
        incoming.knowledge(request.knowledge());
        all.add(incoming);
        var taste=union(all.stream().flatMap(item -> item.slots().taste().stream()).toList());
        var newTaste=request.taste()==null?List.<String>of():request.taste();
        if (newTaste.contains("不辣")) taste=taste.stream().filter(value -> !Set.of("辣","微辣","中辣","重辣","麻辣").contains(value)).toList();
        else if (newTaste.stream().anyMatch(Set.of("辣","微辣","中辣","重辣","麻辣")::contains))
            taste=taste.stream().filter(value -> !"不辣".equals(value)).toList();
        var merged=new MealRequest(primary.name(),
                union(all.stream().flatMap(item -> item.slots().mealTime().stream()).toList()),
                union(all.stream().flatMap(item -> item.slots().mood().stream()).toList()),
                union(all.stream().flatMap(item -> item.slots().scene().stream()).toList()),
                union(all.stream().flatMap(item -> item.slots().healthGoal().stream()).toList()),
                union(all.stream().flatMap(item -> item.slots().cuisine().stream()).toList()),taste,
                union(all.stream().flatMap(item -> item.slots().convenience().stream()).toList()));
        var known=mergeKnowledge(all);
        if (known!=null) merged.knowledge(known);
        MealItem updated=updatePersonalMeal(userId,primary.id(),merged);
        for (int i=1;i<matches.size();i++) {
            MealItem extra=matches.get(i);
            mealMapper.repointPersonalFeedback(userId,extra.id(),primary.id());
            deletePersonalMeal(userId,extra.id());
        }
        return updated;
    }

    private List<MealItem> sameNamedPersonal(Long userId,String name) {
        var rows=mealMapper.findPersonalMeals(userId);
        if (rows==null) return List.of();
        String key=normalizeName(name);
        return attachKnowledge(rows.stream().filter(row -> normalizeName(row.getName()).equals(key))
                .map(this::toMealItem).toList());
    }
    private String normalizeName(String name) { return name.strip().replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    private void claimMealName(Long userId,String name,boolean merging) {
        if (guardJdbc==null) return;
        try {
            if (merging) guardJdbc.update("INSERT INTO personal_meal_name_guard(user_id,normalized_name,created_at) "
                    + "VALUES (?,?,NOW()) ON DUPLICATE KEY UPDATE normalized_name=VALUES(normalized_name)",userId,normalizeName(name));
            else guardJdbc.update("INSERT INTO personal_meal_name_guard(user_id,normalized_name,created_at) VALUES (?,?,NOW())",
                    userId,normalizeName(name));
        } catch (DuplicateKeyException duplicate) { throw new DuplicateMealException(); }
    }
    private void releaseMealName(Long userId,String name) {
        if (guardJdbc!=null) guardJdbc.update("DELETE FROM personal_meal_name_guard WHERE user_id=? AND normalized_name=?",
                userId,normalizeName(name));
    }
    private List<String> union(List<String> values) { return List.copyOf(new LinkedHashSet<>(values)); }
    private com.diet.service.meal.knowledge.MealKnowledge mergeKnowledge(List<MealItem> meals) {
        var known=meals.stream().map(MealItem::knowledge).filter(java.util.Objects::nonNull).toList();
        if (known.isEmpty()) return null;
        var ingredients=union(known.stream().flatMap(item -> item.ingredients().stream()).toList());
        if (ingredients.size()>64) throw new DietException("合并后的食材太多，请先手动整理同名餐食");
        String description=mergeText(known.stream().map(com.diet.service.meal.knowledge.MealKnowledge::description).toList(),2000);
        String preparation=mergeText(known.stream().map(com.diet.service.meal.knowledge.MealKnowledge::preparation).toList(),4000);
        return new com.diet.service.meal.knowledge.MealKnowledge(ingredients,description,preparation,"USER_PROVIDED","");
    }
    private String mergeText(List<String> values,int limit) {
        String text=String.join("\n——\n",new LinkedHashSet<>(values.stream().filter(v -> v!=null && !v.isBlank()).toList()));
        if (text.length()>limit) throw new DietException("合并后的餐食描述太长，请先手动整理同名餐食");
        return text;
    }

    @Transactional
    public MealItem updatePersonalMeal(Long userId, Long mealId, MealRequest request) {
        validateMealRequest(request);
        validateDietaryRestrictions(userId,request);
        MealItemRow existing=mealMapper.findPersonalById(mealId,userId);
        boolean renamed=existing!=null && !normalizeName(existing.getName()).equals(normalizeName(request.name()));
        if (renamed) {
            claimMealName(userId,request.name(),false);
            if (sameNamedPersonal(userId,request.name()).stream().anyMatch(item -> !item.id().equals(mealId)))
                throw new DuplicateMealException();
        }
        MealItemRow row = toRow(mealId, SourceMode.PERSONAL, userId, request);
        int updated = mealMapper.updatePersonal(row);
        if (updated == 0) {
            throw new DietException("个人餐食不存在或无权限修改");
        }
        saveKnowledge(mealId, request);
        if (renamed && sameNamedPersonal(userId,existing.getName()).isEmpty())
            releaseMealName(userId,existing.getName());
        return attachKnowledge(List.of(toMealItem(mealMapper.findPersonalById(mealId, userId)))).get(0);
    }

    @Transactional
    public void deletePersonalMeal(Long userId, Long mealId) {
        MealItemRow existing=mealMapper.findPersonalById(mealId,userId);
        int deleted = mealMapper.deletePersonal(mealId, userId);
        if (deleted == 0) {
            throw new DietException("个人餐食不存在或无权限删除");
        }
        if (knowledgeRepository != null) knowledgeRepository.delete(mealId);
        if (existing!=null && sameNamedPersonal(userId,existing.getName()).isEmpty()) releaseMealName(userId,existing.getName());
    }

    /**
     * 按槽位标签检索餐食并计算初排 matchScore。
     * 由 MealSearchService#search 调用；MySQL JSON_OVERLAPS 召回后 Java 侧 overlap 打分。
     */
    public List<MealItem> search(SourceMode sourceMode, Long userId, SlotBundle slots) {
        return search(sourceMode, userId, slots, MealConstraints.empty());
    }

    public List<MealItem> search(SourceMode sourceMode, Long userId, SlotBundle slots, MealConstraints constraints) {
        MealConstraints safeConstraints = constraints == null ? MealConstraints.empty() : constraints;
        if(memoryPolicy!=null) safeConstraints=memoryPolicy.mealConstraints(userId,safeConstraints);
        return queryMeals(sourceMode,userId,slots,safeConstraints);
    }

    /** Internal planning entry: constraints already merged from the server's frozen memory. */
    @Transactional(readOnly=true,timeout=3)
    public List<MealItem> searchFrozen(SourceMode sourceMode,Long userId,SlotBundle slots,MealConstraints constraints) {
        return attachKnowledge(queryMeals(sourceMode,userId,slots,constraints));
    }

    @Transactional(readOnly=true,timeout=3)
    public List<MealItem> findPlanningMeals(SourceMode mode,Long userId,List<Long> ids) {
        if(ids.isEmpty())return List.of();
        if(ids.size()>60)throw new IllegalArgumentException("规划候选数量超限");
        return attachKnowledge(mealMapper.findForPlanning(mode,userId,ids).stream().map(this::toMealItem).toList());
    }

    private List<MealItem> queryMeals(SourceMode sourceMode,Long userId,SlotBundle slots,MealConstraints safeConstraints) {
        List<MealItemRow> rows = mealMapper.search(
                sourceMode,
                userId,
                jsonService.toJsonArray(slots.mealTime()),
                jsonService.toJsonArray(slots.mood()),
                jsonService.toJsonArray(slots.scene()),
                jsonService.toJsonArray(slots.healthGoal()),
                jsonService.toJsonArray(slots.cuisine()),
                jsonService.toJsonArray(slots.taste()),
                jsonService.toJsonArray(slots.convenience()),
                jsonService.toJsonArray(safeConstraints.requiredIngredients()),
                jsonService.toJsonArray(safeConstraints.excludedIngredients()),
                SEARCH_LIMIT
        );
        return rows.stream().map(this::toMealItem).toList();
    }

    private void validateMealRequest(MealRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new DietException("餐食名称不能为空");
        }
        if (request.knowledge() != null && !"USER_PROVIDED".equals(request.knowledge().sourceType())) throw new DietException("个人录入的知识来源必须为 USER_PROVIDED");
        SlotBundle slots = request.toSlots();
        if (slots.mealTime().isEmpty()) {
            throw new DietException("餐次至少选择一个标签");
        }
        slotOptionService.validate(slots);
    }

    private void validateDietaryRestrictions(Long userId,MealRequest request) {
        if (dietaryProfiles==null || userId==null) return;
        var profile=dietaryProfiles.get(userId);
        var taste=request.taste()==null?List.<String>of():request.taste();
        if ("NEVER".equals(profile.spicePreference()) &&
                taste.stream().anyMatch(Set.of("辣","微辣","中辣","重辣","麻辣")::contains))
            throw new DietException("你设置了不吃辣，请先去掉辣味标签或修改饮食限制");
        if ("MILD".equals(profile.spicePreference()) &&
                taste.stream().anyMatch(Set.of("辣","中辣","重辣","麻辣")::contains))
            throw new DietException("你设置了只吃微辣，请先调整辣味标签");
        var ingredients=request.knowledge()==null?List.<String>of():request.knowledge().ingredients();
        for (String item:profile.allergens()) if (request.name().contains(item)
                || ingredients.stream().anyMatch(value -> value.contains(item)))
            throw new DietException("餐食涉及已设置的过敏原“"+item+"”，请先核实并修改餐食");
        for (String item:profile.avoidedFoods()) if (request.name().contains(item)
                || ingredients.stream().anyMatch(value -> value.contains(item)))
            throw new DietException("餐食涉及已设置的忌口“"+item+"”，请先核实并修改餐食");
    }

    private void saveKnowledge(Long id, MealRequest request) {
        if (request.knowledgeProvided() && knowledgeRepository != null) {
            knowledgeRepository.save(id, request.knowledge());
        }
    }

    private List<MealItem> attachKnowledge(List<MealItem> items) {
        if (knowledgeRepository == null || items.isEmpty()) return items;
        var knowledgeByMealId = knowledgeRepository.findByMealIds(items.stream().map(MealItem::id).toList());
        return items.stream().map(item -> {
            item.knowledge(knowledgeByMealId.get(item.id()));
            return item;
        }).toList();
    }

    private MealItemRow toRow(Long id, SourceMode sourceMode, Long ownerUserId, MealRequest request) {
        SlotBundle slots = request.toSlots();
        MealItemRow row = new MealItemRow();
        row.setId(id);
        row.setSourceType(sourceMode.name());
        row.setOwnerUserId(ownerUserId);
        row.setName(request.name().trim());
        row.setMealTime(jsonService.toJsonArray(slots.mealTime()));
        row.setMood(jsonService.toJsonArray(slots.mood()));
        row.setScene(jsonService.toJsonArray(slots.scene()));
        row.setHealthGoal(jsonService.toJsonArray(slots.healthGoal()));
        row.setCuisine(jsonService.toJsonArray(slots.cuisine()));
        row.setTaste(jsonService.toJsonArray(slots.taste()));
        row.setConvenience(jsonService.toJsonArray(slots.convenience()));
        return row;
    }

    private MealItem toMealItem(MealItemRow row) {
        if (row == null) {
            return null;
        }
        SlotBundle slots = new SlotBundle(
                jsonService.fromJsonArray(row.getMealTime()),
                jsonService.fromJsonArray(row.getMood()),
                jsonService.fromJsonArray(row.getScene()),
                jsonService.fromJsonArray(row.getHealthGoal()),
                jsonService.fromJsonArray(row.getCuisine()),
                jsonService.fromJsonArray(row.getTaste()),
                jsonService.fromJsonArray(row.getConvenience())
        );
        return new MealItem(
                row.getId(),
                SourceMode.valueOf(row.getSourceType()),
                row.getOwnerUserId(),
                row.getName(),
                slots,
                0
        );
    }
}
