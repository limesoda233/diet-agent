package com.diet.service.meal.planning.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Only final, server-validated versions are stored. No LLM calls hold this transaction. */
public class PlanningRecordStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public PlanningRecordStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json) {this.jdbc=jdbc;this.tx=tx;this.json=json;}
    public String save(long user,String session,String trace,String status,String context,String spec,String candidates,String result) {
        return save(user,session,trace,status,context,spec,candidates,result,System.nanoTime()+3_000_000_000L);
    }
    public String save(long user,String session,String trace,String status,String context,String spec,String candidates,String result,long deadlineNanos) {
        checkDeadline(deadlineNanos);
        var boundedTx=new TransactionTemplate(tx.getTransactionManager());
        boundedTx.setTimeout((int)Math.min(3,Math.max(1,(deadlineNanos-System.nanoTime()+999_999_999L)/1_000_000_000L)));
        return boundedTx.execute(s->{
            if(jdbc.queryForObject("SELECT COUNT(*) FROM diet_sessions WHERE id=? AND user_id=?",Integer.class,session,user)!=1)
                throw new IllegalArgumentException("会话不属于当前用户");
            checkDeadline(deadlineNanos);
            String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO meal_plan(id,user_id,session_id,trace_id,current_version,created_at) VALUES (?,?,?,?,1,?)",id,user,session,trace,now);
            jdbc.update("INSERT INTO meal_plan_version(plan_id,version,status,context_json,spec_json,candidates_json,result_json,created_at) VALUES (?,1,?,?,?,?,?,?)",id,status,context,spec,candidates,result,now);
            checkDeadline(deadlineNanos);
            return id;
        });
    }
    private void checkDeadline(long deadlineNanos) {
        if(Thread.currentThread().isInterrupted()||System.nanoTime()>=deadlineNanos)throw new IllegalStateException("计划提交超时或已取消");
    }
    public void assertFeedback(long user,String session,String trace,String plan,Integer version,String task,Long meal) {
        if(plan==null||version==null||task==null||trace==null||meal==null)throw new IllegalArgumentException("计划反馈需要完整版本和任务信息");
        var rows=jdbc.queryForList("SELECT v.result_json FROM meal_plan p JOIN meal_plan_version v ON v.plan_id=p.id WHERE p.id=? AND p.user_id=? AND p.session_id=? AND p.trace_id=? AND v.version=?",String.class,plan,user,session,trace,version);
        try {
            if(rows.size()==1)for(var day:json.readTree(rows.get(0)).path("days"))for(var block:day.path("blocks"))
                if(task.equals(block.path("taskId").asText()) && block.path("selectedMeal").path("mealId").isIntegralNumber() && meal.equals(block.path("selectedMeal").path("mealId").longValue()))return;
        } catch(com.fasterxml.jackson.core.JsonProcessingException malformed) {throw new IllegalStateException("计划记录无法读取",malformed);}
        throw new IllegalArgumentException("反馈未对应当前用户实际展示的计划任务");
    }
    public boolean requiresPlanIdentity(long user,String session,String trace) {
        return trace!=null&&!trace.isBlank()&&jdbc.queryForObject("SELECT COUNT(*) FROM meal_plan WHERE user_id=? AND session_id=? AND trace_id=?",Integer.class,user,session,trace)>0;
    }
    public void recordFeedback(long user,com.diet.model.FeedbackRequest request) {
        if(!java.util.Set.of("LIKE","ADOPT","DISLIKE").contains(request.action())||(request.reason()!=null&&request.reason().length()>512))throw new IllegalArgumentException("计划反馈内容无效");
        var boundedTx=new TransactionTemplate(tx.getTransactionManager());boundedTx.setTimeout(3);
        boundedTx.executeWithoutResult(s->{
            assertFeedback(user,request.sessionId(),request.traceId(),request.planId(),request.planVersion(),request.taskId(),request.itemId());
            jdbc.update("INSERT INTO meal_plan_feedback(plan_id,plan_version,task_id,user_id,session_id,trace_id,item_id,action,reason,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE action=VALUES(action),reason=VALUES(reason),updated_at=VALUES(updated_at)",request.planId(),request.planVersion(),request.taskId(),user,request.sessionId(),request.traceId(),request.itemId(),request.action(),request.reason(),System.currentTimeMillis());
        });
    }
}
