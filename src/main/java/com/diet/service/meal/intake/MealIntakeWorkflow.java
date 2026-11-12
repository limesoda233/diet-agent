package com.diet.service.meal.intake;

import com.diet.model.*;
import com.diet.service.trace.AgentTraceService;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.*;
import java.util.regex.Pattern;
import com.diet.exception.DietException;
import com.diet.exception.DuplicateMealException;
import com.diet.service.meal.nutrition.MealNutritionVariantService;

@Service
public class MealIntakeWorkflow {
    private final MealDraftService drafts;
    private final MealDraftSaveService saves;
    private final AgentTraceService trace;
    public MealIntakeWorkflow(MealDraftService drafts,MealDraftSaveService saves,AgentTraceService trace) {
        this.drafts=drafts; this.saves=saves; this.trace=trace;
    }
    private static final Pattern LIBRARY = Pattern.compile("(?:录入|保存|加入|添加).*(?:餐食库|个人库)");
    private static final Pattern NEW = Pattern.compile("(?:录入|新增|添加)(?:一道|一份)?餐食(?=$|[，。！？、\\s])|(?:录入|新增|添加)(?:一道|一份)?[\\p{IsHan}A-Za-z0-9]{1,40}(?:饭|面|菜|汤|粥|鸡|鱼|肉|蛋|饺子|包子|沙拉|牛排|豆腐|丁|骨|锅|饼|粉|寿司|披萨)(?=$|[，。！？、\\s])");
    private static final Pattern EDIT = Pattern.compile("(?:(?:草稿|餐食).*(?:修改|改成|改为|补充|添加|加上|清空|去掉|删除))|(?:(?:修改|补充|清空|去掉|删除).*草稿)|^(?:餐次|菜名|名字|口味|食材|做法|描述|心情|场景|菜系|健康目标|便捷度)(?:修改|改成|改为|补充|添加|加上|清空|去掉|删除)");
    private static final Pattern NOT_A_REQUEST = Pattern.compile("如何|怎么|怎样|不要|别|不想|不必|不用|不再|禁止|不\\s*$");
    private static final Set<String> TIMES = Set.of("早餐","午餐","晚餐","早上","上午","中午","晚上");

    /** Empty means this turn belongs to the existing recommendation / recipe routes. */
    public Optional<Result> handle(Long userId,ChatRequest request,SessionState state) {
        String text=request.message().trim();
        MealDraft draft=state.mealDraft();
        boolean actionProvided=request.context()!=null && request.context().containsKey("mealDraftAction");
        String action=null;
        Map<?,?> reviewedTags=null;
        MealNutritionVariantService.Input nutritionVariant=null;
        if (actionProvided) {
            if (!Objects.equals(userId,state.userId())) return result(state,"草稿不属于当前用户，请重新录入。",false);
            Object raw=request.context().get("mealDraftAction");
            if (!(raw instanceof Map<?,?> map) || !(map.get("draftId") instanceof String id) || id.isBlank()
                    || !(map.get("action") instanceof String value) || !Set.of("SAVE","CANCEL","MERGE").contains(value))
                return result(state,"草稿操作无效，请使用当前草稿的确认保存或取消按钮。",false);
            if (draft==null || !id.equals(draft.draftId())) return result(state,"这份草稿已过期，请使用最新草稿或重新录入。",false);
            action=value;
            if (map.containsKey("tags")) {
                if (!(map.get("tags") instanceof Map<?,?> tags))
                    return result(state,"草稿标签无效，请重新核对。",false);
                reviewedTags=tags;
            }
            if (map.containsKey("nutritionVariant")) {
                try { nutritionVariant=MealNutritionVariantService.Input.from(map.get("nutritionVariant")); }
                catch (DietException invalid) { return result(state,invalid.getMessage(),false); }
            }
        } else if (text.equals("确认保存")) action="SAVE";
        else if (text.equals("取消录入") || text.equals("取消草稿")) action="CANCEL";
        if (action!=null) {
            if (!Objects.equals(userId,state.userId())) return result(state,"草稿不属于当前用户，请重新录入。",false);
            if (draft==null) return result(state,"当前没有餐食草稿，请先明确要求录入一道餐食。",false);
            if (draft.status()==MealDraftStatus.CANCELLED) return result(state,"这份草稿已取消，请重新录入。",false);
            if (action.equals("CANCEL")) {
                if (draft.status()==MealDraftStatus.SAVED) return result(state,"这份草稿已经保存，取消录入不能删除餐食。",false);
                event("MEAL_DRAFT_CANCELLED",draft);
                return result(state.withMealDraft(draft.cancelled()),"已取消录入，未创建餐食。",false);
            }
            if (draft.status()!=MealDraftStatus.READY && draft.status()!=MealDraftStatus.AWAITING_MERGE
                    && draft.status()!=MealDraftStatus.SAVED)
                return result(state,"草稿还缺少菜名或餐次，请补齐后再确认保存。",false);
            try {
                SessionState saved=saves.save(userId,draft.draftId(),state,reviewedTags,"MERGE".equals(action),nutritionVariant);
                event("MEAL_DRAFT_SAVED",saved.mealDraft());
                return result(saved,("MERGE".equals(action) ? "已合并到" : "已保存到")
                        +"你的个人餐食库，餐食 ID："+saved.mealDraft().savedMealId()+"。",true);
            } catch (DuplicateMealException duplicate) {
                return result(state.withMealDraft(draft.awaitingMerge()),
                        "你的个人餐食库已有同名餐食。核对标签后点击“合并到已有餐食”，会保留并合并原有标签。",false);
            } catch (RuntimeException failure) {
                event("MEAL_DRAFT_SAVE_FAILED",draft);
                String message=failure instanceof DietException ? failure.getMessage() :
                        "餐食保存失败，本次未保存。草稿已保留，请重试确认保存。";
                return result(state,message,true);
            }
        }
        boolean start=explicitIntake(text,NEW) || explicitIntake(text,LIBRARY);
        boolean active=draft!=null && (draft.status()==MealDraftStatus.READY || draft.status()==MealDraftStatus.NEEDS_INPUT
                || draft.status()==MealDraftStatus.AWAITING_MERGE);
        boolean missingReply=active && draft.status()==MealDraftStatus.NEEDS_INPUT
                && ((draft.missingFields().contains("mealTime") && TIMES.contains(text))
                    || (draft.missingFields().contains("name") && dishNameReply(text)));
        if (!start && !(active && (missingReply || EDIT.matcher(text).find()))) return Optional.empty();
        if (!Objects.equals(userId,state.userId())) return result(state,"草稿不属于当前用户，请重新录入。",false);
        String input=text;
        if (start && text.matches(".*(?:这道菜|这个菜|这份餐食|它).*")) {
            String selected=state.recipeConversation().activeDish();
            if (selected.isBlank()) return result(state,"请明确告诉我需要录入的菜名和餐次。",false);
            input=text+"\n明确选中的菜名："+selected;
        }
        try {
            MealDraft generated=drafts.generate(userId,input,start ? null : draft);
            event(start ? "MEAL_DRAFT_CHAT_GENERATED" : "MEAL_DRAFT_CLARIFIED",generated);
            String speech=generated.status()==MealDraftStatus.READY ? "餐食草稿已整理，请核对后确认保存，或继续明确修改草稿。"
                    : "餐食草稿已整理，请补充"+(generated.missingFields().contains("name") ? "菜名" : "")
                        +(generated.missingFields().size()==2 ? "和" : "")+(generated.missingFields().contains("mealTime") ? "餐次（早餐、午餐或晚餐）" : "")+"。";
            return result(state.withMealDraft(generated),speech,false);
        } catch (DietException failure) {
            if (trace != null) trace.recordEvent("MEAL_DRAFT_CHAT_FAILED","MEAL_INTAKE",Map.of("entry","CHAT"),Map.of("reason","generation"));
            return result(state,failure.getMessage(),false);
        }
    }
    private boolean explicitIntake(String text, Pattern route) {
        var match=route.matcher(text);
        while (match.find()) {
            int boundary=Math.max(Math.max(text.lastIndexOf('，',match.start()),text.lastIndexOf('。',match.start())),
                    Math.max(text.lastIndexOf('；',match.start()),text.lastIndexOf('！',match.start())));
            String lead=text.substring(boundary+1,match.start());
            if (!NOT_A_REQUEST.matcher(lead).find()) return true;
        }
        return false;
    }
    private boolean dishNameReply(String text) {
        return text.matches("(?:菜名(?:是|叫)|名字(?:是|叫)|叫).{1,128}")
                || (text.length()<=24 && !text.matches(".*(?:什么|怎么|如何|怎样|哪些|哪道|能做|可以做|[？?]).*")
                    && text.matches("[\\p{IsHan}A-Za-z0-9]+(?:饭|面|菜|汤|粥|鸡|鱼|肉|蛋|丁|饺子|包子|沙拉|牛排)")
                    && !TIMES.contains(text));
    }
    private Optional<Result> result(SessionState state,String text,boolean persisted) { return Optional.of(new Result(state,text,persisted)); }
    private void event(String name,MealDraft draft) {
        if (trace!=null) trace.recordEvent(name,"MEAL_INTAKE",Map.of("entry","CHAT"),Map.of("draftId",draft.draftId(),"status",draft.status()));
    }
    public record Result(SessionState state,String speechText,boolean persisted) { }
}
