package com.diet.service.meal.planning;

import java.time.*;
import java.util.*;
import java.util.regex.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Resolves only explicit supported ranges. Model output cannot supply the date anchor. */
public final class MealPlanningRequestParser {
    private static final List<String> MEALS=List.of("早餐","午餐","晚餐");
    private static final String NUMERALS="零〇一二两三四五六七八九十百千万0-9";
    private static final Pattern LOCAL_DAY=Pattern.compile("第["+NUMERALS+"]+天|(?:最后|前|后)["+NUMERALS+"]+天");
    private static final Pattern SOFT_REPEAT=Pattern.compile("(?:尽量|尽可能|最好)不(?:重复|重样)");
    private final Clock clock;
    public MealPlanningRequestParser(Clock clock) {this.clock=clock;}
    public Range parse(Input input) {
        LocalDate anchor=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        String text=input.message(); Map<String,Object> data=input.structured();List<String> errors=new ArrayList<>();
        if(!Set.of("startDate","days","mealTimes","repeatPolicy").containsAll(data.keySet()))errors.add("结构化规划字段不受支持");
        if(Pattern.compile("大后天|昨天|前天|下下?周|上周|本周|这周|周末|明后天|今明|(?:未来|接下来|连续)["+NUMERALS+"]+(?:周|星期|个月|月)").matcher(text).find()
                ||Pattern.compile("从[^，,；;。\\n]{0,60}(?:到|至)").matcher(text).find())errors.add("请改用明确的单个起始日期和1至3个连续自然日，暂不支持该日期表达");
        if(Pattern.compile("夜宵|宵夜|加餐|下午茶|早午餐").matcher(text).find())errors.add("第一版仅支持早餐、午餐和晚餐，请明确完整餐次范围");
        validateMealPolarity(text,errors);
        validateLocalMealScope(text,errors);
        if(text.matches("(?s).*(换掉|替换|改掉|修改).*(第[一二三123]天|计划).*" ) ||text.matches("(?s).*第[一二三123]天.*(换掉|替换|改掉|修改).*")) errors.add("请重新提供需要规划的完整日期和餐次，暂不支持修改已有计划");
        LocalDate start=null;int days=1;
        Matcher span=Pattern.compile("(?:未来|接下来|连续)(["+NUMERALS+"]+)天").matcher(text);
        List<int[]> bounds=new ArrayList<>();
        while(span.find()) {
            int count=number(span.group(1));
            if(!bounds.isEmpty()&&count!=days)errors.add("日期范围包含相互冲突的天数，请澄清");
            if(bounds.isEmpty())days=count;
            bounds.add(new int[]{span.start(),span.end()});start=anchor;
            if(count<1||count>3)errors.add("第一版仅支持1至3个连续自然日");
        }
        Matcher duration=Pattern.compile("(?<![第前后"+NUMERALS+"])(["+NUMERALS+"]+)天").matcher(text);
        while(duration.find()) {
            int from=duration.start(),to=duration.end();
            if(bounds.stream().noneMatch(bound->from>=bound[0]&&to<=bound[1]))errors.add("未识别的天数范围，请明确起始日期及连续天数");
        }
        Matcher explicit=Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text);
        List<LocalDate> dates=new ArrayList<>();
        while(explicit.find()) {try{dates.add(LocalDate.parse(explicit.group()));}catch(DateTimeException ex){errors.add("日期无效");}}
        int relativeCount=0;
        for(String term:List.of("今天","明天","后天"))if(text.contains(term)){relativeCount++;start=anchor.plusDays(term.equals("今天")?0:term.equals("明天")?1:2);}
        if(relativeCount>1||dates.size()>1)errors.add("请提供一个连续日期范围");
        if(dates.size()==1) {
            if(relativeCount==1&&!dates.get(0).equals(start))errors.add("相对日期与明确日期冲突，请澄清");
            start=dates.get(0);
        }
        List<String> meals=MEALS.stream().filter(text::contains).toList();
        if(text.contains("三餐")||text.contains("一日三餐"))meals=MEALS;
        boolean softRepeat=SOFT_REPEAT.matcher(text).find();
        boolean hardRepeat=Pattern.compile("不要重复|不能重复|不许重复|不重复|不要重样|不能重样|不许重样|不重样|别重复|别重样").matcher(SOFT_REPEAT.matcher(text).replaceAll("")).find();
        if(softRepeat&&hardRepeat)errors.add("暂不支持按餐次区分硬性与尽量去重，请明确统一的去重要求");
        RepeatPolicy repeat=hardRepeat?RepeatPolicy.HARD:softRepeat?RepeatPolicy.SOFT:RepeatPolicy.ALLOW;
        try {
            if(data.containsKey("startDate")) {LocalDate structured=LocalDate.parse((String)data.get("startDate"));if(start!=null&&!start.equals(structured))errors.add("原文和结构化日期冲突");start=structured;}
            if(data.containsKey("days")) {Object value=data.get("days");if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException();int n=Math.toIntExact(((Number)value).longValue());if(span.reset().find()&&n!=days)errors.add("原文和结构化天数冲突");days=n;}
            if(data.containsKey("mealTimes")) {Object value=data.get("mealTimes");if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>3||list.stream().anyMatch(v->!(v instanceof String)||!MEALS.contains(v))||new HashSet<>(list).size()!=list.size())throw new IllegalArgumentException();List<String> normalized=MEALS.stream().filter(list::contains).toList();if(!meals.isEmpty()&&!new HashSet<>(meals).equals(new HashSet<>(normalized)))errors.add("原文和结构化餐次冲突");meals=normalized;}
            if(data.containsKey("repeatPolicy")) {RepeatPolicy structured=RepeatPolicy.valueOf((String)data.get("repeatPolicy"));if(repeat!=RepeatPolicy.ALLOW&&structured!=repeat)errors.add("不能放宽或替换原文明示的去重要求");repeat=structured;}
        } catch(RuntimeException ex) {errors.add("规划范围字段格式无效");}
        if(meals.isEmpty()&&!input.slots().mealTime().isEmpty())meals=input.slots().mealTime();
        validateLocalDayReferences(text,days,errors);
        if(start==null)errors.add("请明确规划日期，例如今天、明天或未来三天");
        if(days<1||days>3)errors.add("第一版仅支持1至3个连续自然日");
        if(meals.isEmpty()||!MEALS.containsAll(meals)||meals.size()*days>9)errors.add("请明确早餐、午餐、晚餐中的餐次");
        return new Range(start,days,meals,repeat,errors);
    }
    private void validateMealPolarity(String text,List<String> errors) {
        String meal="(?:早餐|午餐|晚餐)";
        if(Pattern.compile("(?:(?:不(?:要|用)?(?:安排|需要|吃)|不要|别(?:安排)?|跳过|取消|排除)\\s*"+meal+"|"+meal+"\\s*(?:不安排|不需要|不用|除外|排除))").matcher(text).find())
            errors.add("请直接列出需要安排的餐次，暂不自动解释否定或排除餐次的范围");
        Matcher exclusive=Pattern.compile("(?:只|仅)(?:需要|安排|规划|计划|吃|要|有)?("+meal+"(?:(?:和|及|、|与|以及)"+meal+")*)").matcher(text);
        List<String> mentioned=mealNames(text);
        while(exclusive.find())if(!mentioned.equals(mealNames(exclusive.group(1)))) {
            errors.add("仅安排的餐次与其他餐次表述有冲突，请只列出本次完整餐次范围");break;
        }
    }
    private void validateLocalDayReferences(String text,int days,List<String> errors) {
        Matcher local=LOCAL_DAY.matcher(text);
        while(local.find()) {
            String reference=local.group();
            if(reference.matches("第[一二三123]天")) {
                if(number(reference.substring(1,2))>days)errors.add("局部日期超出本轮规划范围，请澄清");
            } else if(reference.matches("[前后][两二2]天")) {
                if(days<2)errors.add("前两天或后两天需要至少两天的规划范围，请澄清");
            } else errors.add("暂不支持该局部日期表达，请使用范围内的第一、第二或第三天");
        }
    }
    private void validateLocalMealScope(String text,List<String> errors) {
        Matcher local=LOCAL_DAY.matcher(text);List<Integer> starts=new ArrayList<>();
        while(local.find())starts.add(local.start());
        if(starts.isEmpty())return;
        List<String> common=mealNames(text.substring(0,starts.get(0)));
        for(int i=0;i<starts.size();i++) {
            List<String> scoped=mealNames(text.substring(starts.get(i),i+1<starts.size()?starts.get(i+1):text.length()));
            if(!scoped.isEmpty()&&(common.isEmpty()||!common.equals(scoped))) {
                errors.add("暂不支持每天选择不同餐次，请明确所有日期共同安排的餐次；口味偏好可以按日期指定");return;
            }
        }
    }
    private List<String> mealNames(String text) {return text.contains("三餐")?MEALS:MEALS.stream().filter(text::contains).toList();}
    private int number(String n) {return switch(n){case "一"->1;case "二","两"->2;case "三"->3;default->{try{yield Integer.parseInt(n);}catch(NumberFormatException ex){yield 4;}}};}
    public Spec deterministic(Range range,FrozenContext context) {
        List<Task> tasks=new ArrayList<>();
        for(int day=0;day<range.days();day++)for(String meal:range.mealTimes()) {
            String id="d"+day+"-"+switch(meal){case "早餐"->"breakfast";case "午餐"->"lunch";default->"dinner";};
            List<String> refs=context.preferences().stream().filter(p->p.taskIds().isEmpty()||p.taskIds().contains(id)).map(Preference::preferenceId).toList();
            tasks.add(new Task(id,day,meal,refs));
        }
        return new Spec(tasks,List.of());
    }
}


