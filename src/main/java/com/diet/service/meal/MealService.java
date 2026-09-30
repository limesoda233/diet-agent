package com.diet.service.meal;

import com.diet.exception.DietException;
import com.diet.mapper.MealMapper;
import com.diet.model.MealItem;
import com.diet.model.MealItemRow;
import com.diet.model.MealRequest;
import com.diet.model.SlotBundle;
import com.diet.enums.SourceMode;
import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 餐食数据服务。
 * 提供 CRUD 和基于 MySQL JSON_OVERLAPS 的标签检索；Orchestrator 推荐链路通过 {@link #search} 召回候选。
 */
@Service
public class MealService {

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
        MealItemRow row = toRow(null, SourceMode.PERSONAL, userId, request);
        mealMapper.insert(row);
        saveKnowledge(row.getId(), request);
        return attachKnowledge(List.of(toMealItem(row))).get(0);
    }

    @Transactional
    public MealItem updatePersonalMeal(Long userId, Long mealId, MealRequest request) {
        validateMealRequest(request);
        MealItemRow row = toRow(mealId, SourceMode.PERSONAL, userId, request);
        int updated = mealMapper.updatePersonal(row);
        if (updated == 0) {
            throw new DietException("个人餐食不存在或无权限修改");
        }
        saveKnowledge(mealId, request);
        return attachKnowledge(List.of(toMealItem(mealMapper.findPersonalById(mealId, userId)))).get(0);
    }

    @Transactional
    public void deletePersonalMeal(Long userId, Long mealId) {
        int deleted = mealMapper.deletePersonal(mealId, userId);
        if (deleted == 0) {
            throw new DietException("个人餐食不存在或无权限删除");
        }
        if (knowledgeRepository != null) knowledgeRepository.delete(mealId);
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
