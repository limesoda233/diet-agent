package com.diet.service.meal.intake;

import com.diet.exception.DietException;
import com.diet.mapper.SlotOptionMapper;
import com.diet.service.slot.SlotOptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealDraftServiceTest {
    @Test void acceptsOneExactJsonCodeFenceButStillValidatesEvidence() {
        var draft = service("""
                ```json
                {"name":{"value":"鸡丁","evidence":"鸡丁"}}
                ```
                """).generate("鸡丁");
        assertThat(draft.data().name()).isEqualTo("鸡丁");
        assertThatThrownBy(() -> service("""
                ```json
                {"name":{"value":"宫保鸡丁","evidence":"鸡丁"}}
                ```
                """).generate("鸡丁")).isInstanceOf(DietException.class);
    }

    @Test void addsCompatibleSuggestedMealTimesButKeepsExplicitBreakfast() {
        var draft = service("""
                {"name":{"value":"番茄鸡蛋面","evidence":"番茄鸡蛋面"},
                 "mealTime":[{"value":"早餐","evidence":"早上"}],
                 "suggestions":{"mealTime":["午餐","晚餐"],"taste":["清淡"]}}
                """).generate("录入番茄鸡蛋面，早上吃");
        assertThat(draft.data().mealTime()).containsExactly("早餐", "午餐", "晚餐");
        assertThat(draft.data().taste()).containsExactly("清淡");
    }

    @Test void noSpiceInstructionOverridesSpicyDishNameAndSuggestions() {
        var draft = service("""
                {"name":{"value":"麻辣烫","evidence":"麻辣烫"},
                 "mealTime":[{"value":"午餐","evidence":"午餐"}],
                 "taste":[{"value":"微辣","evidence":"麻辣烫"}],
                 "suggestions":{"taste":["微辣","不辣"]}}
                """).generate("录入麻辣烫，午餐吃，不加辣");
        assertThat(draft.data().taste()).containsExactly("不辣");
    }
    @Test void explicitExclusionsBeatModelSuggestions() {
        var draft = service("""
                {"name":{"value":"番茄鸡蛋面","evidence":"番茄鸡蛋面"},
                 "mealTime":[{"value":"早餐","evidence":"早上"}],
                 "taste":[{"value":"清淡","evidence":"清淡"}],
                 "ingredients":[{"value":"香菜","evidence":"香菜"}],
                 "suggestions":{"mealTime":["午餐","晚餐"],"cuisine":["川菜"]}}
                """).generate("录入番茄鸡蛋面，只在早上吃，清淡，不放香菜，也不要川菜");
        assertThat(draft.data().mealTime()).containsExactly("早餐");
        assertThat(draft.data().cuisine()).isEmpty();
        assertThat(draft.data().knowledge().ingredients()).isEmpty();
    }
    @Test void savedNeverSpicyPreferenceOverridesModelSuggestions() {
        var profiles=mock(com.diet.service.profile.DietaryProfileService.class);
        when(profiles.get(7L)).thenReturn(new com.diet.service.profile.DietaryProfileService.Profile(
                List.of(),List.of(),List.of(),"NEVER","COMPLETED",""));
        String output="""
                {"name":{"value":"麻辣烫","evidence":"麻辣烫"},
                 "mealTime":[{"value":"午餐","evidence":"午餐"}],
                 "suggestions":{"taste":["微辣","不辣"]}}
                """;
        var draft=new MealDraftService((system,user) -> Mono.just(output),options(),new ObjectMapper(),
                new MealIntakeProperties(),null,profiles).generate(7L,"录入麻辣烫，午餐",null);
        assertThat(draft.data().taste()).containsExactly("不辣");
    }
    static final String COMPLETE = """
            {"name":{"value":"宫保鸡丁","evidence":"宫保鸡丁"},
             "mealTime":[{"value":"午餐","evidence":"中午吃"}],
             "taste":[{"value":"微辣","evidence":"微辣"}],
             "ingredients":[{"value":"鸡肉","evidence":"鸡肉和花生"},{"value":"花生","evidence":"鸡肉和花生"}]}
            """;

    @Test void generatesReadyDraftWithoutGuessingOptionalTags() {
        var draft = service(COMPLETE).generate(" 宫保鸡丁，中午吃，微辣，鸡肉和花生 ");
        assertThat(draft.status()).isEqualTo(MealDraftStatus.READY);
        assertThat(draft.originalText()).isEqualTo(" 宫保鸡丁，中午吃，微辣，鸡肉和花生 ");
        assertThat(draft.data().mealTime()).containsExactly("午餐");
        assertThat(draft.data().cuisine()).isEmpty();
        assertThat(draft.data().knowledge().ingredients()).containsExactly("鸡肉", "花生");
        assertThat(draft.data().knowledge().preparation()).isEmpty();
        assertThat(draft.data().knowledge().sourceType()).isEqualTo("USER_PROVIDED");
        assertThat(draft.data().toMealRequest().knowledgeProvided()).isTrue();
        assertThat(draft.savedMealId()).isNull();
    }

    @Test void missingRequiredFieldsRemainIncomplete() {
        var named = service("{\"name\":{\"value\":\"番茄鸡蛋面\",\"evidence\":\"番茄鸡蛋面\"}}")
                .generate("帮我录入番茄鸡蛋面");
        assertThat(named.missingFields()).containsExactly("mealTime");
        assertThat(named.status()).isEqualTo(MealDraftStatus.NEEDS_INPUT);
        var unnamed = service("{\"ingredients\":[{\"value\":\"鸡肉\",\"evidence\":\"鸡肉\"}]}")
                .generate("有鸡肉，帮我录入一份餐食");
        assertThat(unnamed.data().name()).isEmpty();
        assertThat(unnamed.missingFields()).containsExactly("name", "mealTime");
    }

    @Test void dropsUnknownAndUnsupportedFieldsWithWarnings() {
        var draft = service("""
                {"name":{"value":"宫保鸡丁","evidence":"宫保鸡丁"},
                 "mealTime":[{"value":"宵夜","evidence":"宵夜"}],
                 "cuisine":[{"value":"川菜","evidence":"宫保鸡丁"}],
                 "preparation":{"value":"炒五分钟","evidence":"宫保鸡丁"}}
                """).generate("宫保鸡丁，宵夜");
        assertThat(draft.data().mealTime()).isEmpty();
        assertThat(draft.data().cuisine()).isEmpty();
        assertThat(draft.data().knowledge().preparation()).isEmpty();
        assertThat(draft.warnings()).hasSize(3);
        assertThat(draft.evidence()).containsOnlyKeys("name");
    }

    @Test void sparseUpdateRetainsEvidenceAndExplicitEmptyArrayClears() {
        var previous = service(COMPLETE).generate("宫保鸡丁，中午吃，微辣，鸡肉和花生");
        var updated = service("{\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚上\"}],\"taste\":[]}")
                .generate("改成晚上，口味清空", previous);
        assertThat(updated.draftId()).isEqualTo(previous.draftId());
        assertThat(updated.data().name()).isEqualTo("宫保鸡丁");
        assertThat(updated.data().mealTime()).containsExactly("晚餐");
        assertThat(updated.data().taste()).isEmpty();
        assertThat(updated.data().knowledge().ingredients()).containsExactly("鸡肉", "花生");
        assertThat(updated.evidence().get("name")).containsExactly("宫保鸡丁");
        assertThat(updated.evidence()).doesNotContainKey("taste");
        assertThat(updated.originalText()).isEqualTo("宫保鸡丁，中午吃，微辣，鸡肉和花生\n改成晚上，口味清空");
    }

    @Test void multilineIngredientSupplementRetainsPriorIngredientsAndEvidence() {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}]} ")
                .generate("原来食材有番茄");
        var updated = service("{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"},{\"value\":\"葱\",\"evidence\":\"葱\"}]}")
                .generate("食材补充鸡蛋\n也加葱",previous);
        assertThat(updated.data().knowledge().ingredients()).containsExactly("番茄","鸡蛋","葱");
        assertThat(updated.evidence().get("ingredients")).containsExactly("番茄","鸡蛋","葱");
    }

    @Test void ingredientSupplementAndMealTimeEditInEitherLineOrderRemainIndependent() {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}],\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"午餐\"}]}")
                .generate("原来食材有番茄，午餐吃");
        String response = "{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}],\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]}";
        for (String turn : List.of("食材补充鸡蛋\n餐次改成晚餐", "餐次改成晚餐\n食材补充鸡蛋")) {
            var updated = service(response).generate(turn,previous);
            assertThat(updated.data().knowledge().ingredients()).containsExactly("番茄","鸡蛋");
            assertThat(updated.evidence().get("ingredients")).containsExactly("番茄","鸡蛋");
            assertThat(updated.data().mealTime()).containsExactly("晚餐");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"、", "，", ",", "。", ".", "；", ";"})
    void ingredientSupplementAndMealTimeEditSeparatedByPunctuationRemainIndependent(String separator) {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}]}")
                .generate("原来食材有番茄");
        String response = "{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}],\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]}";
        for (String turn : List.of("餐次改成晚餐"+separator+"食材补充鸡蛋", "食材补充鸡蛋"+separator+"餐次改成晚餐")) {
            var updated = service(response).generate(turn,previous);
            assertThat(updated.data().knowledge().ingredients()).containsExactly("番茄","鸡蛋");
            assertThat(updated.evidence().get("ingredients")).containsExactly("番茄","鸡蛋");
            assertThat(updated.data().mealTime()).containsExactly("晚餐");
        }
    }

    @Test void ingredientRemovalContinuationReplacesPriorIngredientList() {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}]}")
                .generate("原来食材有番茄");
        var updated = service("{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}]}")
                .generate("食材补充鸡蛋、去掉番茄",previous);
        assertThat(updated.data().knowledge().ingredients()).containsExactly("鸡蛋");
        assertThat(updated.evidence().get("ingredients")).containsExactly("鸡蛋");
    }

    @Test void removalContinuationAfterMealTimeEditDoesNotReplaceIngredients() {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}],\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"午餐\"}]}")
                .generate("原来食材有番茄，午餐吃");
        var updated = service("{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}],\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]}")
                .generate("食材补充鸡蛋、餐次改成晚餐、去掉午餐",previous);
        assertThat(updated.data().knowledge().ingredients()).containsExactly("番茄","鸡蛋");
        assertThat(updated.data().mealTime()).containsExactly("晚餐");
    }

    @ParameterizedTest @ValueSource(strings = {"去掉午餐", "去掉中午", "改成晚餐", "午餐改成晚餐"})
    void shortMealTimeValueEditSwitchesAwayFromIngredientContext(String mealTimeEdit) {
        var previous = service("{\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}],\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"午餐\"}]}")
                .generate("原来食材有番茄，午餐吃");
        String mealTime = mealTimeEdit.contains("去掉") ? "[]" : "[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]";
        String response = "{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}],\"mealTime\":"+mealTime+"}";
        var updated = service(response).generate("食材补充鸡蛋、"+mealTimeEdit,previous);
        assertThat(updated.data().knowledge().ingredients()).containsExactly("番茄","鸡蛋");
        assertThat(updated.evidence().get("ingredients")).containsExactly("番茄","鸡蛋");
        assertThat(updated.data().mealTime()).isEqualTo(mealTimeEdit.contains("去掉") ? List.of() : List.of("晚餐"));
    }

    @Test void doesNotLaunderPreviousDraftWithoutEvidence() {
        var previous = new MealDraft("d", "中午", new MealDraftData("伪造菜名", List.of("午餐"),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null), List.of(),List.of(),
                Map.of("name",List.of("伪造菜名"),"mealTime",List.of("中午")), MealDraftStatus.READY,null);
        var actual = service("{\"taste\":[{\"value\":\"微辣\",\"evidence\":\"微辣\"}]}")
                .generate("微辣", previous);
        assertThat(actual.data().name()).isEmpty();
        assertThat(actual.missingFields()).containsExactly("name");
        assertThat(actual.warnings()).isNotEmpty();
    }

    @Test void retainedEvidenceMustAlreadyExistInPreviousOriginalText() {
        var previous = new MealDraft("d", "中午", new MealDraftData("伪造菜名", List.of("午餐"),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null), List.of(),List.of(),
                Map.of("name",List.of("伪造菜名"),"mealTime",List.of("中午")), MealDraftStatus.READY,null);
        var actual = service("{\"taste\":[{\"value\":\"微辣\",\"evidence\":\"微辣\"}]}")
                .generate("伪造菜名，微辣",previous);
        assertThat(actual.data().name()).isEmpty();
    }

    @Test void emptyUpdateCannotBeReportedAsModelSuccess() {
        var previous = service(COMPLETE).generate("宫保鸡丁，中午吃，微辣，鸡肉和花生");
        assertThatThrownBy(() -> service("{}").generate("改一改",previous)).isInstanceOf(DietException.class);
    }

    @Test void historicalEvidenceCannotRestoreClearedMealTime() {
        var previous = service(COMPLETE).generate("宫保鸡丁，中午吃，微辣，鸡肉和花生");
        var cleared = service("{\"mealTime\":[]}").generate("清空餐次",previous);
        assertThat(cleared.data().mealTime()).isEmpty();
        var updated = service("""
                {"taste":[{"value":"清淡","evidence":"清淡"}],
                 "mealTime":[{"value":"午餐","evidence":"中午吃"}]}
                """).generate("改成清淡",cleared);
        assertThat(updated.data().mealTime()).isEmpty();
        assertThat(updated.evidence()).doesNotContainKey("mealTime");
        assertThat(updated.status()).isEqualTo(MealDraftStatus.NEEDS_INPUT);
        assertThat(updated.warnings()).anyMatch(w -> w.contains("mealTime"));
    }

    @Test void historicalEvidenceCannotOverwriteModifiedMealTime() {
        var previous = service(COMPLETE).generate("宫保鸡丁，中午吃，微辣，鸡肉和花生");
        var modified = service("{\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]}")
                .generate("改成晚餐",previous);
        var updated = service("""
                {"taste":[{"value":"清淡","evidence":"清淡"}],
                 "mealTime":[{"value":"午餐","evidence":"中午吃"}]}
                """).generate("改成清淡",modified);
        assertThat(updated.data().mealTime()).containsExactly("晚餐");
        assertThat(updated.evidence().get("mealTime")).containsExactly("晚餐");
        assertThat(updated.warnings()).anyMatch(w -> w.contains("mealTime"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"mealTime\":[{\"value\":\"宵夜\",\"evidence\":\"宵夜\"}]}",
            "{\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚上\"}]}",
            "{\"name\":{\"value\":\"炒面\",\"evidence\":\"炒面\"}}"})
    void rejectedNonemptyUpdatesPreserveExistingValuesAndEvidence(String response) {
        var previous = service(COMPLETE).generate("宫保鸡丁，中午吃，微辣，鸡肉和花生");
        var updated = service(response).generate("改成清淡",previous);
        assertThat(updated.data().name()).isEqualTo("宫保鸡丁");
        assertThat(updated.data().mealTime()).containsExactly("午餐");
        assertThat(updated.evidence().get("name")).containsExactly("宫保鸡丁");
        assertThat(updated.evidence().get("mealTime")).containsExactly("中午吃");
        assertThat(updated.status()).isEqualTo(MealDraftStatus.READY);
        assertThat(updated.warnings()).isNotEmpty();
    }

    @Test void emptyScalarUpdatesDoNotClearNameOrPreparation() {
        var previous = service("""
                {"name":{"value":"鸡丁","evidence":"鸡丁"},
                 "mealTime":[{"value":"午餐","evidence":"中午"}],
                 "preparation":{"value":"先腌后炒","evidence":"先腌后炒"}}
                """).generate("鸡丁，中午，先腌后炒");
        var updated = service("{\"name\":{\"value\":\"\",\"evidence\":\"\"},\"preparation\":{\"value\":\"\",\"evidence\":\"\"}}")
                .generate("调整一下",previous);
        assertThat(updated.data().name()).isEqualTo("鸡丁");
        assertThat(updated.data().knowledge().preparation()).isEqualTo("先腌后炒");
        assertThat(updated.evidence().get("preparation")).containsExactly("先腌后炒");
        assertThat(updated.warnings()).hasSize(2);
    }

    @Test void rejectedKnowledgeUpdatesPreserveExistingUserKnowledge() {
        var previous = service("""
                {"name":{"value":"鸡丁","evidence":"鸡丁"},
                 "ingredients":[{"value":"鸡肉","evidence":"鸡肉"}],
                 "preparation":{"value":"先腌后炒","evidence":"先腌后炒"}}
                """).generate("鸡丁，鸡肉，先腌后炒");
        var updated = service("""
                {"ingredients":[{"value":"牛肉","evidence":"牛肉"}],
                 "preparation":{"value":"煮熟","evidence":"煮熟"}}
                """).generate("调整一下",previous);
        assertThat(updated.data().knowledge().ingredients()).containsExactly("鸡肉");
        assertThat(updated.data().knowledge().preparation()).isEqualTo("先腌后炒");
        assertThat(updated.warnings()).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"not json", "{}", "[]", "null", "{\"name\":1}",
            "{\"ownerUserId\":2}", "{\"sourceType\":\"PUBLIC\"}", "{\"mealId\":1}",
            "{\"multipleMeals\":true}", "{\"multipleMeals\":\"false\"}",
            "{\"name\":{},\"name\":{}}", "{} {}", "{\"taste\":[\"微辣\"]}",
            "{\"name\":{\"value\":\"鸡丁\",\"evidence\":\"鸡丁\",\"userId\":1}}"})
    void rejectsInvalidModelResponses(String response) {
        assertThatThrownBy(() -> service(response).generate("鸡丁，微辣"))
                .isInstanceOf(DietException.class).hasMessageMatching(".*[\u4e00-\u9fff].*");
    }

    @Test void rejectsFabricatedNameAndPreparationWhenNoValidExtractionRemains() {
        assertThatThrownBy(() -> service("{\"name\":{\"value\":\"宫保鸡丁\",\"evidence\":\"鸡肉\"}}")
                .generate("鸡肉")).isInstanceOf(DietException.class);
        assertThatThrownBy(() -> service("{\"preparation\":{\"value\":\"炒熟\",\"evidence\":\"炒熟\"}}")
                .generate("鸡肉")).isInstanceOf(DietException.class);
    }

    @Test void enforcesInputAndDomainLengthsWithoutLosingOriginalText() {
        String input = "鸡丁" + "啊".repeat(3998);
        var draft = service("{\"name\":{\"value\":\"鸡丁\",\"evidence\":\"鸡丁\"}}") .generate(input);
        assertThat(draft.originalText()).isEqualTo(input);
        assertThat(draft.data().knowledge().description()).hasSize(2000);
        assertThat(draft.warnings()).anyMatch(w -> w.contains("2000"));
        assertThatThrownBy(() -> service(COMPLETE).generate("啊".repeat(4001))).isInstanceOf(DietException.class);
        assertThatThrownBy(() -> service(COMPLETE).generate(" ")).isInstanceOf(DietException.class);
        assertThatThrownBy(() -> service(COMPLETE).generate("更多", draft)).isInstanceOf(DietException.class);
    }

    @Test void rejectsOversizedNameAndIngredients() throws Exception {
        String name = "菜".repeat(129);
        String nameResponse = new ObjectMapper().writeValueAsString(Map.of("name",Map.of("value",name,"evidence",name)));
        assertThatThrownBy(() -> service(nameResponse).generate(name)).isInstanceOf(DietException.class);
        String ingredient = "肉".repeat(65);
        String finalResponse = new ObjectMapper().writeValueAsString(Map.of("ingredients",List.of(Map.of("value",ingredient,"evidence",ingredient))));
        assertThatThrownBy(() -> service(finalResponse).generate(ingredient)).isInstanceOf(DietException.class);
    }

    @Test void outerTimeoutAndEmptyModelNeverReturnSuccessOrRetry() {
        var properties = new MealIntakeProperties(); properties.setTimeout(Duration.ofMillis(30));
        assertThatThrownBy(() -> new MealDraftService((s,u) -> Mono.never(), options(),new ObjectMapper(),properties,null)
                .generate("鸡丁")).isInstanceOf(DietException.class);
        assertThatThrownBy(() -> new MealDraftService((s,u) -> Mono.empty(), options(),new ObjectMapper(),properties,null)
                .generate("鸡丁")).isInstanceOf(DietException.class);
        assertThatThrownBy(() -> new MealDraftService((s,u) -> {throw new IllegalStateException("secret backend details");},
                options(),new ObjectMapper(),properties,null).generate("鸡丁"))
                .isInstanceOf(DietException.class).hasMessageNotContaining("secret");
    }

    static MealDraftService service(String response) {
        return new MealDraftService((system,user) -> Mono.just(response), options(), new ObjectMapper(),new MealIntakeProperties(),null);
    }
    static SlotOptionService options() {
        var mapper = mock(SlotOptionMapper.class);
        when(mapper.findEnabledValues(anyString())).thenReturn(List.of());
        when(mapper.findEnabledValues("mealTime")).thenReturn(List.of("早餐","午餐","晚餐"));
        when(mapper.findEnabledValues("taste")).thenReturn(List.of("微辣","清淡","不辣"));
        when(mapper.findEnabledValues("cuisine")).thenReturn(List.of("川菜"));
        return new SlotOptionService(mapper);
    }
}
