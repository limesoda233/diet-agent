package com.diet.service.meal.planning;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Adapters must remain read-only until the single final repository call. */
public final class PlanningPorts {
    private PlanningPorts() {}
    public interface ContextPort {
        Mono<FrozenContext> freeze(Input input,Duration timeout);
        Mono<Boolean> unchanged(Input input,FrozenContext context,Duration timeout);
    }
    public interface CandidatePort {
        Mono<Pool> fetch(Input input,FrozenContext context,Task task,int limit,Duration timeout);
        Mono<Boolean> unchanged(Input input,FrozenContext context,Snapshot snapshot,Duration timeout);
    }
    public interface RolePort { Mono<String> call(Role role,JsonNode data,Duration timeout); }
    public interface RepositoryPort { Mono<PlanIdentity> save(Input input,FrozenContext context,Spec spec,Snapshot snapshot,Result result); }
    public interface Events { void emit(Input input,String event,Map<String,Object> data); }
}
