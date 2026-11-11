package com.diet.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.util.*;

/** All writes serialize on a user's profile row. Models never execute inside these transactions. */
@Repository
public class MemoryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final RowMapper<MemoryItem> ITEM = (r,n) -> new MemoryItem(r.getString("id"),r.getLong("user_id"),
            r.getString("memory_type"),r.getString("memory_value"),r.getString("stance"),r.getString("status"),
            r.getString("source_type"),r.getString("evidence"),r.getObject("source_message_id",Long.class),
            r.getLong("last_job_id"),r.getLong("version"),r.getObject("expires_at",Long.class),r.getLong("updated_at"));
    private static final RowMapper<MemoryJob> JOB = (r,n) -> new MemoryJob(r.getLong("id"),r.getLong("user_id"),
            r.getString("session_id"),r.getLong("message_id"),r.getString("trace_id"),r.getLong("epoch"),r.getString("content"),r.getInt("attempts"));

    public MemoryStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper ignored) { this.jdbc=jdbc; this.tx=tx; }

    private void ensure(long userId) {
        if(userId<=0) throw new IllegalArgumentException("用户编号无效");
        jdbc.update("INSERT INTO user_memory_profile(user_id,collection_enabled,epoch) VALUES (?,TRUE,0) ON DUPLICATE KEY UPDATE user_id=user_id",userId);
    }
    private Map<String,Object> lock(long userId) {
        ensure(userId);
        return jdbc.queryForMap("SELECT collection_enabled,epoch FROM user_memory_profile WHERE user_id=? FOR UPDATE",userId);
    }
    public boolean collectionEnabled(long userId) {
        ensure(userId);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT collection_enabled FROM user_memory_profile WHERE user_id=?",Boolean.class,userId));
    }
    public void recordExposure(long user,String session,String trace,List<com.diet.model.MealResponse> meals) {
        tx.executeWithoutResult(s->{
            var profile=lock(user);if(!Boolean.TRUE.equals(profile.get("collection_enabled")))return;
            for(var meal:meals) if(meal!=null && meal.id()!=null)
                jdbc.update("INSERT INTO user_memory_exposure(user_id,session_id,trace_id,item_id,created_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE item_id=item_id",user,session,trace,meal.id(),System.currentTimeMillis());
        });
    }
    public void recordFeedback(long user,com.diet.model.FeedbackRequest request) {
        tx.executeWithoutResult(s->{
            var profile=lock(user);if(!Boolean.TRUE.equals(profile.get("collection_enabled")))return;
            if(request.traceId()==null || request.traceId().isBlank())return; // Legacy feedback is not precise enough for memory evidence.
            if(!Set.of("LIKE","ADOPT","DISLIKE").contains(request.action()) || request.itemId()==null
                    || (request.reason()!=null && request.reason().length()>512))throw new IllegalArgumentException("反馈内容无效");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_exposure WHERE user_id=? AND session_id=? AND trace_id=? AND item_id=?",Integer.class,user,request.sessionId(),request.traceId(),request.itemId())!=1)
                throw new IllegalArgumentException("该反馈未对应当前用户实际展示的推荐");
            jdbc.update("INSERT INTO user_memory_feedback(id,user_id,session_id,trace_id,item_id,action,reason,created_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE action=VALUES(action),reason=VALUES(reason),created_at=VALUES(created_at)",UUID.randomUUID().toString(),user,request.sessionId(),request.traceId(),request.itemId(),request.action(),request.reason(),System.currentTimeMillis());
        });
    }
    public void setCollection(long userId, boolean enabled) {
        tx.executeWithoutResult(s -> { lock(userId); jdbc.update("UPDATE user_memory_profile SET collection_enabled=?,epoch=epoch+1 WHERE user_id=?",enabled,userId); });
    }
    public MemoryJob appendTurn(long userId,String sessionId,String message,String traceId) {
        if(message==null || message.isBlank() || message.length()>8000) throw new IllegalArgumentException("消息长度须为1到8000字符");
        return tx.execute(s -> {
            var profile=lock(userId);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM diet_sessions WHERE id=? AND user_id=?",Integer.class,sessionId,userId)!=1)
                throw new IllegalArgumentException("会话不属于当前用户");
            var key=new GeneratedKeyHolder();
            jdbc.update(c -> { var p=c.prepareStatement("INSERT INTO diet_messages(session_id,role,content,intent,agent_trace_id,created_at) VALUES (?,'user',?,NULL,?,CURRENT_TIMESTAMP)",Statement.RETURN_GENERATED_KEYS);
                p.setString(1,sessionId);p.setString(2,message);p.setString(3,traceId);return p;},key);
            long messageId=Objects.requireNonNull(key.getKey()).longValue();
            long epoch=((Number)profile.get("epoch")).longValue();
            if(!Boolean.TRUE.equals(profile.get("collection_enabled"))) return new MemoryJob(0,userId,sessionId,messageId,traceId,epoch,message,0);
            var jobKey=new GeneratedKeyHolder();
            long now=System.currentTimeMillis();
            jdbc.update(c -> {var p=c.prepareStatement("INSERT INTO user_memory_job(user_id,session_id,message_id,trace_id,epoch,status,attempts,available_at,created_at) VALUES (?,?,?,?,?,'PENDING',0,?,?)",Statement.RETURN_GENERATED_KEYS);
                p.setLong(1,userId);p.setString(2,sessionId);p.setLong(3,messageId);p.setString(4,traceId);p.setLong(5,epoch);p.setLong(6,now);p.setLong(7,now);return p;},jobKey);
            return new MemoryJob(Objects.requireNonNull(jobKey.getKey()).longValue(),userId,sessionId,messageId,traceId,epoch,message,0);
        });
    }
    public List<MemoryItem> list(long userId) {
        return jdbc.query("SELECT * FROM user_memory WHERE user_id=? AND status<>'DELETED' AND (expires_at IS NULL OR expires_at>?) ORDER BY updated_at DESC,id",ITEM,userId,System.currentTimeMillis());
    }
    public PlanningMemorySnapshot planningSnapshot(long userId) {
        var boundedTx=new TransactionTemplate(tx.getTransactionManager());
        boundedTx.setTimeout(3);
        return boundedTx.execute(s -> {
            var profile=lock(userId);
            return new PlanningMemorySnapshot(((Number)profile.get("epoch")).longValue(),list(userId));
        });
    }
    public List<MemoryJob> pending(int limit) {
        long now=System.currentTimeMillis();
        jdbc.update("UPDATE user_memory_job SET status='FAILED',lease_token=NULL,error_type='RetryBudgetExhausted' WHERE attempts>=5 AND ((status='RUNNING' AND lease_until<?) OR status='PENDING')",now);
        return jdbc.query("SELECT j.*,m.content FROM user_memory_job j JOIN diet_messages m ON m.id=j.message_id WHERE (j.status='PENDING' AND j.available_at<=?) OR (j.status='RUNNING' AND j.lease_until<?) ORDER BY j.id LIMIT ?",JOB,now,now,Math.min(Math.max(limit,1),100));
    }
    public String claim(long id) {
        String token=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
        int changed=jdbc.update("UPDATE user_memory_job SET status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1 WHERE id=? AND attempts<5 AND ((status='PENDING' AND available_at<=?) OR (status='RUNNING' AND lease_until<?))",token,now+60000,id,now,now);
        return changed==1?token:null;
    }
    public boolean complete(long id,String token) {
        return jdbc.update("UPDATE user_memory_job SET status='DONE',lease_token=NULL,lease_until=NULL,error_type=NULL WHERE id=? AND status='RUNNING' AND lease_token=?",id,token)==1;
    }
    public void fail(long id,String token,String error) {
        jdbc.update("UPDATE user_memory_job SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,available_at=?,lease_token=NULL,lease_until=NULL,error_type=? WHERE id=? AND lease_token=?",System.currentTimeMillis()+30000,error,id,token);
    }
    public List<MemoryItem> apply(MemoryJob job,String token,List<MemoryFact> facts) {
        return tx.execute(s -> {
            lock(job.userId());
            var owned=jdbc.queryForList("SELECT id FROM user_memory_job WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE",job.id(),token,System.currentTimeMillis());
            if(owned.isEmpty()) return List.of();
            var changed=new ArrayList<MemoryItem>();
            for(var fact:facts) {
                recordEvidence(job,fact);
                if(!"LONG_TERM".equals(fact.scope())) continue;
                if("REMOVE".equals(fact.operation())) { revokeFromMessage(job,fact); continue; }
                var item=remember(job,fact.type(),fact.value(),fact.stance(),"EXPLICIT",fact.evidence(),false);
                if(item!=null) changed.add(item);
            }
            complete(job.id(),token);
            return List.copyOf(changed);
        });
    }
    private boolean validEpoch(MemoryJob job,Map<String,Object> profile) {
        return Boolean.TRUE.equals(profile.get("collection_enabled")) && ((Number)profile.get("epoch")).longValue()==job.epoch();
    }
    public MemoryItem remember(MemoryJob job,String type,String value,String stance,String source,String evidence,boolean inferred) {
        return tx.execute(s -> {
            var profile=lock(job.userId());
            if(!validEpoch(job,profile)) return null;
            var existing=jdbc.query("SELECT * FROM user_memory WHERE user_id=? AND memory_type=? AND memory_value=?",ITEM,job.userId(),type,value);
            MemoryItem old=existing.isEmpty()?null:existing.get(0);
            if(old!=null && ((inferred && "DELETED".equals(old.status())) || old.lastJobId()>=job.id() || (inferred && "EXPLICIT".equals(old.sourceType())))) return null;
            String status="ALLERGEN".equals(type)?"PENDING":"ACTIVE";
            if(old!=null && "ACTIVE".equals(old.status()) && "ALLERGEN".equals(type)) status="ACTIVE";
            String id=old==null?UUID.randomUUID().toString():old.id(); long now=System.currentTimeMillis();
            Long expires=inferred?now+30L*24*3600*1000:null;
            if(old==null) jdbc.update("INSERT INTO user_memory(id,user_id,memory_type,memory_value,stance,status,source_type,evidence,source_message_id,last_job_id,version,expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,1,?,?)",id,job.userId(),type,value,stance,status,source,evidence,job.messageId(),job.id(),expires,now);
            else jdbc.update("UPDATE user_memory SET stance=?,status=?,source_type=?,evidence=?,source_message_id=?,last_job_id=?,version=version+1,expires_at=?,updated_at=? WHERE id=? AND user_id=?",stance,status,source,evidence,job.messageId(),job.id(),expires,now,id,job.userId());
            recordEvidence(job,new MemoryFact(type,value,stance,"LONG_TERM","ADD",evidence));
            event(job.userId(),id,"UPSERT",job.traceId());
            return jdbc.queryForObject("SELECT * FROM user_memory WHERE id=? AND user_id=?",ITEM,id,job.userId());
        });
    }
    private void recordEvidence(MemoryJob job,MemoryFact fact) {
        if(!validEpoch(job,lock(job.userId()))) return;
        String id=UUID.nameUUIDFromBytes((job.id()+"|"+fact.type()+"|"+fact.value()+"|"+fact.stance()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        jdbc.update("INSERT INTO user_memory_evidence(id,user_id,job_id,session_id,memory_type,memory_value,stance,scope,evidence,created_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id,job.userId(),job.id(),job.sessionId(),fact.type(),fact.value(),fact.stance(),fact.scope(),fact.evidence(),System.currentTimeMillis());
    }
    private void revokeFromMessage(MemoryJob job,MemoryFact fact) {
        if("ALLERGEN".equals(fact.type()) || !validEpoch(job,lock(job.userId()))) return;
        var found=jdbc.query("SELECT * FROM user_memory WHERE user_id=? AND memory_type=? AND memory_value=?",ITEM,job.userId(),fact.type(),fact.value());
        if(found.isEmpty()) {
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO user_memory(id,user_id,memory_type,memory_value,stance,status,source_type,evidence,source_message_id,last_job_id,version,expires_at,updated_at) VALUES (?,?,?,?,?,'DELETED','EXPLICIT',?,?,?,1,NULL,?)",id,job.userId(),fact.type(),fact.value(),fact.stance(),fact.evidence(),job.messageId(),job.id(),System.currentTimeMillis());
            event(job.userId(),id,"REMOVE",job.traceId());
        } else if(found.get(0).lastJobId()<job.id()) {
            jdbc.update("UPDATE user_memory SET status='DELETED',version=version+1,last_job_id=?,updated_at=? WHERE user_id=? AND memory_type=? AND memory_value=?",job.id(),System.currentTimeMillis(),job.userId(),fact.type(),fact.value());
            event(job.userId(),found.get(0).id(),"REMOVE",job.traceId());
        }
    }
    public MemoryItem change(long userId,String id,long version,String action,String value,String stance) {
        return tx.execute(s -> {
            lock(userId);
            var found=jdbc.query("SELECT * FROM user_memory WHERE id=? AND user_id=? AND version=? AND status<>'DELETED'",ITEM,id,userId,version);
            if(found.isEmpty()) throw new IllegalArgumentException("记忆不存在、无权访问或版本已更新，请刷新");
            var old=found.get(0);
            if(!Set.of("CONFIRM","DELETE","UPDATE").contains(action)) throw new IllegalArgumentException("未知记忆操作");
            if("CONFIRM".equals(action) && !"PENDING".equals(old.status())) throw new IllegalArgumentException("仅待确认记忆可以确认");
            if("UPDATE".equals(action)) MemoryValidation.validateValue(old.type(),value,stance);
            if("UPDATE".equals(action) && !old.value().equals(value)) {
                // Keep the old key as a tombstone, so a queued old statement cannot restore it.
                jdbc.update("UPDATE user_memory SET status='DELETED',version=version+1,updated_at=? WHERE id=? AND user_id=?",System.currentTimeMillis(),id,userId);
                var duplicate=jdbc.query("SELECT * FROM user_memory WHERE user_id=? AND memory_type=? AND memory_value=?",ITEM,userId,old.type(),value);
                if(!duplicate.isEmpty() && !"DELETED".equals(duplicate.get(0).status())) throw new IllegalArgumentException("相同记忆已存在，请修改该记录");
                String newId=duplicate.isEmpty()?UUID.randomUUID().toString():duplicate.get(0).id();
                String evidence="用户在记忆管理中确认修改："+value+"（"+stance+"）";
                if(duplicate.isEmpty())
                    jdbc.update("INSERT INTO user_memory(id,user_id,memory_type,memory_value,stance,status,source_type,evidence,source_message_id,last_job_id,version,expires_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE','EXPLICIT',?,NULL,?,1,NULL,?)",newId,userId,old.type(),value,stance,evidence,old.lastJobId(),System.currentTimeMillis());
                else jdbc.update("UPDATE user_memory SET stance=?,status='ACTIVE',source_type='EXPLICIT',evidence=?,source_message_id=NULL,last_job_id=?,version=version+1,expires_at=NULL,updated_at=? WHERE id=? AND user_id=?",stance,evidence,Math.max(old.lastJobId(),duplicate.get(0).lastJobId()),System.currentTimeMillis(),newId,userId);
                idForEvent(userId,id,action); return jdbc.queryForObject("SELECT * FROM user_memory WHERE id=?",ITEM,newId);
            }
            String next="DELETE".equals(action)?"DELETED":"ACTIVE";
            if("UPDATE".equals(action))
                jdbc.update("UPDATE user_memory SET status=?,stance=?,source_type='EXPLICIT',evidence=?,source_message_id=NULL,expires_at=NULL,version=version+1,updated_at=? WHERE id=? AND user_id=?",next,stance,"用户在记忆管理中确认修改："+value+"（"+stance+"）",System.currentTimeMillis(),id,userId);
            else jdbc.update("UPDATE user_memory SET status=?,stance=?,source_type='EXPLICIT',expires_at=NULL,version=version+1,updated_at=? WHERE id=? AND user_id=?",next,old.stance(),System.currentTimeMillis(),id,userId);
            idForEvent(userId,id,action);
            return jdbc.queryForObject("SELECT * FROM user_memory WHERE id=? AND user_id=?",ITEM,id,userId);
        });
    }
    private void idForEvent(long user,String id,String action) {
        jdbc.update("UPDATE user_memory_profile SET epoch=epoch+1 WHERE user_id=?",user);event(user,id,action,null);
    }
    private void event(long user,String id,String action,String trace) {
        jdbc.update("INSERT INTO user_memory_event(id,user_id,memory_id,action,trace_id,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),user,id,action,trace,System.currentTimeMillis());
    }
    public int consolidate(long since,int minimumSessions) {
        var groups=jdbc.queryForList("SELECT user_id,memory_type,memory_value,stance,MAX(job_id) job_id,COUNT(DISTINCT session_id) session_count FROM user_memory_evidence WHERE created_at>=? AND memory_type IN ('TASTE','CUISINE') AND stance IN ('LIKE','DISLIKE') GROUP BY user_id,memory_type,memory_value,stance HAVING COUNT(DISTINCT session_id)>=?",since,Math.max(2,minimumSessions));
        int count=0;
        for(var group:groups) {
            long user=((Number)group.get("user_id")).longValue();
            var jobs=jdbc.query("SELECT j.*,m.content FROM user_memory_job j JOIN diet_messages m ON m.id=j.message_id WHERE j.id=? AND j.user_id=?",JOB,group.get("job_id"),user);
            if(jobs.isEmpty()) continue;
            var job=jobs.get(0);
            var evidence=jdbc.queryForList("SELECT evidence FROM user_memory_evidence WHERE job_id=? AND memory_type=? AND memory_value=? AND stance=?",job.id(),group.get("memory_type"),group.get("memory_value"),group.get("stance"));
            if(!evidence.isEmpty() && remember(job,(String)group.get("memory_type"),(String)group.get("memory_value"),(String)group.get("stance"),"INFERRED",(String)evidence.get(0).get("evidence"),true)!=null) count++;
        }
        return count;
    }
}
