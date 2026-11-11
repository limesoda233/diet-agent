package com.diet.service.meal.planning.runtime;

import com.diet.service.meal.MealService;
import com.diet.service.meal.constraint.IngredientCatalog;
import com.diet.service.meal.planning.*;
import com.diet.service.meal.planning.PlanningPorts.*;
import com.diet.service.memory.*;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.*;
import java.time.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="diet.meal-planning.enabled",havingValue="true")
public class MealPlanningConfiguration {
    @Bean(name="planningJdbcScheduler",destroyMethod="dispose")
    Scheduler planningJdbcScheduler(){return Schedulers.newBoundedElastic(3,30,"meal-plan-jdbc");}
    @Bean PlanningRecordStore planningRecordStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){return new PlanningRecordStore(jdbc,tx,json);}
    @Bean PlanningDataAdapter planningDataAdapter(MealService meals,MemoryStore store,ObjectProvider<MemoryPolicy> policies,IngredientCatalog catalog,
            @Qualifier("planningJdbcScheduler") Scheduler scheduler,AgentTraceService trace,ObjectMapper json) {
        return new PlanningDataAdapter(meals,store,policies.getIfAvailable(),catalog,scheduler,trace,json,Clock.system(ZoneId.of("Asia/Shanghai")));
    }
    @Bean AgentScopePlanningRoles planningRoles(@Qualifier("DietLightChatModel") Model light,@Qualifier("DietMainChatModel") Model main,AgentTraceService trace){return new AgentScopePlanningRoles(light,main,trace);}
    @Bean MealPlanningChatService mealPlanningChatService(PlanningDataAdapter data,AgentScopePlanningRoles roles,PlanningRecordStore store,ObjectMapper json,
            @Qualifier("planningJdbcScheduler") Scheduler scheduler,AgentTraceService trace,
            @Value("${diet.meal-planning.total-timeout:40s}") String total,
            @Value("${diet.meal-planning.planner-timeout:5s}") String planner,
            @Value("${diet.meal-planning.menu-timeout:8s}") String menu,
            @Value("${diet.meal-planning.review-timeout:4s}") String review,
            @Value("${diet.meal-planning.retrieval-concurrency:3}") int concurrency) {
        var budget=new Budget(DurationStyle.detectAndParse(total),Duration.ofSeconds(5),Duration.ofSeconds(8),DurationStyle.detectAndParse(planner),DurationStyle.detectAndParse(menu),DurationStyle.detectAndParse(review),concurrency);
        return new MealPlanningChatService(input->{
            long runDeadline=System.nanoTime()+budget.total().toNanos();
            var captured=trace.captureContext();
            ContextPort context=new ContextPort(){
                public Mono<FrozenContext> freeze(Input i,Duration t){return captured.call(()->data.freeze(i,t));}
                public Mono<Boolean> unchanged(Input i,FrozenContext c,Duration t){return captured.call(()->data.unchanged(i,c,t));}
            };
            CandidatePort candidates=new CandidatePort(){
                public Mono<Pool> fetch(Input i,FrozenContext c,Task task,int n,Duration t){return captured.call(()->data.fetch(i,c,task,n,t));}
                public Mono<Boolean> unchanged(Input i,FrozenContext c,Snapshot s,Duration t){return captured.call(()->data.unchanged(i,c,s,t));}
            };
            RolePort model=(r,d,t)->captured.call(()->roles.call(r,d,t));
            RepositoryPort repository=(i,c,s,p,r)->{
                long commitDeadline=Math.min(runDeadline,System.nanoTime()+budget.context().toNanos());
                return Mono.fromCallable(()->new PlanIdentity(store.save(i.userId(),i.sessionId(),i.traceId(),r.status().name(),json.writeValueAsString(c),json.writeValueAsString(s),json.writeValueAsString(p),json.writeValueAsString(r),commitDeadline),1)).subscribeOn(scheduler);
            };
            Events events=(i,event,values)->captured.call(()->{trace.recordEvent(event,"PLAN",null,values);return null;});
            return new MealPlanningCoordinator(context,candidates,model,repository,events,Clock.system(ZoneId.of("Asia/Shanghai")),budget).plan(input);
        });
    }
}
