package com.diet.service.meal.planning;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.MealConstraints;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

class PlanningRequestParserTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T17:00:00Z"), ZoneOffset.UTC);
    @Test void expandsOnlyRequestedDinnersUsingShanghaiServerDate() {
        Range range = new MealPlanningRequestParser(clock).parse(input("未来三天只安排晚餐，尽量不重复", Map.of()));
        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026,10,21));
        assertThat(range.days()).isEqualTo(3);
        assertThat(range.mealTimes()).containsExactly("晚餐");
        assertThat(range.repeatPolicy()).isEqualTo(RepeatPolicy.SOFT);
        assertThat(range.clarifications()).isEmpty();
    }
    @Test void rejectsAmbiguousDatesAndOutOfScopeWithoutSilentTruncation() {
        assertThat(new MealPlanningRequestParser(clock).parse(input("安排几天晚餐", Map.of())).clarifications()).isNotEmpty();
        assertThat(new MealPlanningRequestParser(clock).parse(input("未来四天晚餐", Map.of())).clarifications()).isNotEmpty();
        assertThat(new MealPlanningRequestParser(clock).parse(input("今天和后天晚餐", Map.of())).clarifications()).isNotEmpty();
        assertThat(new MealPlanningRequestParser(clock).parse(input("明天晚餐不要重复", Map.of("repeatPolicy","SOFT"))).clarifications()).isNotEmpty();
    }
    @Test void structuredRangeIsStrictAndDoesNotPermitIdentityFields() {
        var parser = new MealPlanningRequestParser(clock);
        assertThat(parser.parse(input("安排一下", Map.of("startDate","2026-10-22","days",2,"mealTimes",List.of("早餐","晚餐"),"repeatPolicy","HARD"))).clarifications()).isEmpty();
        assertThat(parser.parse(input("明天晚餐", Map.of("userId",99))).clarifications()).isNotEmpty();
        assertThat(parser.parse(input("明天晚餐", Map.of("days",1.5))).clarifications()).isNotEmpty();
    }
    @Test void dayLocalMealScopesRequireClarificationInsteadOfAddingMealsToEveryDay() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("未来三天，第一天早餐，后两天晚餐","未来三天早餐和晚餐，第一天只吃早餐，后两天只吃晚餐","未来三天三餐，第一天早餐清淡"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isNotEmpty();
    }
    @Test void localTastePreferencesPreserveAnExplicitCommonMealRange() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("未来三天只安排晚餐，第一天清淡，后两天川菜","未来三天晚餐，第一天晚餐清淡，后两天晚餐川菜")) {
            Range range=parser.parse(input(message,Map.of()));
            assertThat(range.clarifications()).as(message).isEmpty();
            assertThat(range.days()).isEqualTo(3);assertThat(range.mealTimes()).containsExactly("晚餐");
        }
    }
    @Test void unsupportedRelativeDatesAndDateRangesAreNotSubstringMatched() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("大后天晚餐","昨天晚餐","下周今天晚餐","今天晚餐和周末晚餐","从今天到后天晚餐","明后天晚餐"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isNotEmpty();
    }
    @Test void relativeAndAbsoluteDatesMustResolveToTheSameServerDate() {
        var parser=new MealPlanningRequestParser(clock);
        assertThat(parser.parse(input("明天2026-10-21晚餐",Map.of())).clarifications()).isNotEmpty();
        Range consistent=parser.parse(input("明天2026-10-22晚餐",Map.of()));
        assertThat(consistent.clarifications()).isEmpty();assertThat(consistent.startDate()).isEqualTo(LocalDate.of(2026,10,22));
    }
    @Test void mixedSoftAndHardRepeatClausesRequireClarification() {
        var parser=new MealPlanningRequestParser(clock);
        assertThat(parser.parse(input("未来三天早餐尽量不重复，晚餐不要重复",Map.of())).clarifications()).isNotEmpty();
        assertThat(parser.parse(input("未来三天晚餐尽量不重复，但必须不重复",Map.of())).clarifications()).isNotEmpty();
    }
    @Test void unsupportedExtraMealsOrAdditionalDayBoundsAreNeverIgnored() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("今天晚餐和夜宵","今天早餐和下午茶","今天早餐和早午餐","未来三天连续四天晚餐","明天安排四天晚餐","未来999999999999999999999天晚餐","今天晚餐安排未来一周"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isNotEmpty();
    }
    @Test void localDayReferencesMustExistInsideTheResolvedRangeAndSupportedContextGrammar() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("未来三天晚餐，第四天清淡","未来三天晚餐，第十天川菜","未来三天晚餐，第零天清淡","未来三天晚餐，第0天川菜","未来两天晚餐，第三天清淡","今天晚餐，后两天川菜","今天晚餐，前两天清淡","未来三天晚餐，最后一天清淡","未来三天晚餐，后一天川菜","未来三天晚餐，第01天清淡"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isNotEmpty();
        for(String message:List.of("未来两天晚餐，后两天川菜","未来两天晚餐，前二天清淡","未来三天晚餐，第三天清淡"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isEmpty();
    }
    @Test void explicitHardRepeatSynonymsNeverBecomeAllow() {
        var parser=new MealPlanningRequestParser(clock);
        for(String phrase:List.of("别重样","不能重样","不许重样")) {
            var range=parser.parse(input("未来三天晚餐，"+phrase,Map.of()));
            assertThat(range.clarifications()).as(phrase).isEmpty();
            assertThat(range.repeatPolicy()).as(phrase).isEqualTo(RepeatPolicy.HARD);
        }
        assertThat(parser.parse(input("未来三天早餐尽量不重复，晚餐不能重样",Map.of())).clarifications()).isNotEmpty();
    }
    @Test void negatedExcludedOrConflictingExclusiveMealMentionsRequireClarification() {
        var parser=new MealPlanningRequestParser(clock);
        for(String message:List.of("未来三天不安排早餐，只安排晚餐","未来三天安排三餐，晚餐除外","未来三天早餐不安排，晚餐正常","未来三天只安排晚餐，早餐只是举例","未来三天早餐和午餐不需要，只安排晚餐"))
            assertThat(parser.parse(input(message,Map.of())).clarifications()).as(message).isNotEmpty();
        assertThat(parser.parse(input("未来三天只安排晚餐，第一天清淡",Map.of())).clarifications()).isEmpty();
    }
    @Test void structuredMealsOverrideSessionFallbackButCannotOverrideExplicitText() {
        var parser=new MealPlanningRequestParser(clock);
        var slots=new SlotBundle(List.of("早餐"),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var structured=Map.<String,Object>of("startDate","2026-10-21","mealTimes",List.of("晚餐"));
        var input=new Input(7L,"session","trace",SourceMode.PERSONAL,"安排一下",slots,MealConstraints.empty(),structured);
        var explicit=parser.parse(input);
        assertThat(explicit.clarifications()).isEmpty();assertThat(explicit.mealTimes()).containsExactly("晚餐");
        var fallback=parser.parse(new Input(7L,"session","trace",SourceMode.PERSONAL,"今天安排一下",slots,MealConstraints.empty(),Map.of()));
        assertThat(fallback.clarifications()).isEmpty();assertThat(fallback.mealTimes()).containsExactly("早餐");
        assertThat(parser.parse(new Input(7L,"session","trace",SourceMode.PERSONAL,"今天早餐",slots,MealConstraints.empty(),structured)).clarifications()).isNotEmpty();
    }
    static Input input(String text,Map<String,Object> structured) {
        return new Input(7L,"session","trace",SourceMode.PERSONAL,text,SlotBundle.empty(),MealConstraints.empty(),structured);
    }
}
