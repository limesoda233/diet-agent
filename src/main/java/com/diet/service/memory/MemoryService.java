package com.diet.service.memory;

import com.diet.service.memory.extraction.MemoryExtractor;
import java.util.*;

/** Online extraction is awaited before retrieval. The model is never called within a SQL transaction. */
public class MemoryService {
    private final MemoryStore store;
    private final MemoryExtractor extractor;
    public MemoryService(MemoryStore store,MemoryExtractor extractor) {this.store=store;this.extractor=extractor;}
    public void recordResponse(long user,String session,String trace,com.diet.model.ChatResponse response) {
        store.recordExposure(user,session,trace,response.displayBlocks()==null?List.of():response.displayBlocks());
    }

    public MemoryTurn prepare(long user,String session,String message,String trace) {
        var job=store.appendTurn(user,session,message,trace);
        String token=job.id()==0?null:store.claim(job.id());
        var notices=new ArrayList<String>();
        List<MemoryFact> facts=List.of(); boolean clarify=false;
        try {
            facts=extractFacts(message);
            if(token!=null) {
                var changed=store.apply(job,token,facts);
                for(var item:changed) {
                    notices.add("PENDING".equals(item.status())?"待确认长期记忆："+item.value()+"过敏，请到“我的记忆”确认或撤销。"
                            :"已记住："+item.value()+"（"+("DISLIKE".equals(item.stance())?"不喜欢":"偏好/条件")+"），可在“我的记忆”修改或撤销。");
                }
                if(facts.stream().anyMatch(f->"REMOVE".equals(f.operation()) && "ALLERGEN".equals(f.type())))
                    notices.add("过敏档案不会由聊天自动撤销，请在“我的记忆”中确认修改。");
            }
        } catch(RuntimeException error) {
            if(token!=null) store.fail(job.id(),token,error.getClass().getSimpleName());
            notices.add("本次记忆未完成保存，后台会按任务状态重试；请勿据此认为已经记住。");
            clarify=message.matches("(?s).*(过敏|不能吃|忌口|禁忌|别放|不放|不吃).*" );
        }
        return new MemoryTurn(facts,store.list(user),notices,clarify);
    }
    public void process(MemoryJob job,String token) {
        try {
            var facts=extractFacts(job.message());
            store.apply(job,token,facts);
        } catch(RuntimeException error) {
            store.fail(job.id(),token,error.getClass().getSimpleName());
        }
    }
    private List<MemoryFact> extractFacts(String message) {
        var facts=MemoryValidation.facts(message,extractor.extract(message));
        for(String clause:message.split("[，,。；;！？!?]")) {
            boolean self=clause.matches("(?s).*(?:我(?!妈|爸|朋友|们)|本人|自己).*" );
            boolean hypotheticalOrNegative=clause.matches("(?s).*(如果|假如|假设|不过敏|不对.*过敏|没有.*过敏).*" );
            if(!self || hypotheticalOrNegative)continue;
            if(clause.contains("过敏") && facts.stream().noneMatch(f->"ALLERGEN".equals(f.type()) && clause.contains(f.value())))
                throw new IllegalArgumentException("明确过敏声明未被提取");
            if(clause.matches("(?s).*(不能吃|忌口|禁忌).*" ) && facts.stream().noneMatch(f->Set.of("ALLERGEN","AVOID_INGREDIENT").contains(f.type()) && clause.contains(f.value())))
                throw new IllegalArgumentException("明确禁忌未被提取");
        }
        return facts;
    }
}
