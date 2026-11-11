package com.diet.service.meal.planning;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.diet.service.meal.planning.PlanningTypes.*;
import static com.diet.service.meal.planning.PlanningPorts.*;

/** Bounded, request-local state machine. Agents propose; Java owns every final decision. */
public final class MealPlanningCoordinator {
    private final ContextPort contexts;
    private final CandidatePort candidates;
    private final RolePort roles;
    private final RepositoryPort repository;
    private final Events events;
    private final Clock clock;
    private final Budget budget;
    private final PlanningJsonCodec json=new PlanningJsonCodec();
    private final PlanValidator validator=new PlanValidator();
    private final MealPlanningRequestParser parser;

    public MealPlanningCoordinator(ContextPort contexts,CandidatePort candidates,RolePort roles,
                                   RepositoryPort repository,Events events,Clock clock,Budget budget) {
        this.contexts=Objects.requireNonNull(contexts);this.candidates=Objects.requireNonNull(candidates);
        this.roles=Objects.requireNonNull(roles);this.repository=Objects.requireNonNull(repository);
        this.events=Objects.requireNonNull(events);this.clock=Objects.requireNonNull(clock);this.budget=Objects.requireNonNull(budget);
        this.parser=new MealPlanningRequestParser(clock);
    }
    public Mono<Result> plan(Input input) {
        return Mono.defer(()->{
            Run r=new Run(input,parser.parse(input));
            if(!r.range.clarifications().isEmpty())return Mono.just(empty(r,Status.NEEDS_INPUT,r.range.clarifications()));
            return bounded(r,budget.context(),()->contexts.freeze(input,allowance(r,budget.context())))
                    .flatMap(context->{
                        r.context=context;emit(r,"PLAN_CONTEXT_READY",Map.of());
                        if(!context.blockers().isEmpty()||!context.unsupportedConstraints().isEmpty())return Mono.just(empty(r,Status.NEEDS_INPUT,context.blockers()));
                        return propose(r).flatMap(spec->{
                            r.spec=spec;
                            if(!spec.clarifications().isEmpty())return Mono.just(empty(r,Status.NEEDS_INPUT,spec.clarifications()));
                            emit(r,"PLAN_SPEC_VALIDATED",Map.of("taskCount",spec.tasks().size()));
                            return retrieve(r).flatMap(snapshot->{
                                r.snapshot=snapshot;
                                if(snapshot.pools().values().stream().allMatch(Pool::failed))return Mono.just(empty(r,Status.UNAVAILABLE,List.of("ALL_RETRIEVAL_FAILED")));
                                return compose(r,false).flatMap(draft->{r.validation=validate(r,draft);return reviewAndRepair(r);}).then(Mono.defer(()->finish(r)));
                            });
                        });
                    }).timeout(budget.total()).onErrorResume(error->Mono.just(empty(r,Status.UNAVAILABLE,List.of(isDeadline(r)?"PLANNING_DEADLINE":"PLANNING_UNAVAILABLE"))));
        });
    }
    private Mono<Spec> propose(Run r) {
        Spec deterministic=parser.deterministic(r.range,r.context);
        return invoke(r,Role.PLANNER,payload(r,deterministic),budget.planner()).map(json::spec)
                .map(spec->{
                    emit(r,"PLAN_SPEC_PROPOSED",Map.of("taskCount",spec.tasks().size()));
                    if(!spec.clarifications().isEmpty())return spec;
                    List<String> errors=validator.validateSpec(r.range,r.context,spec);
                    if(!errors.isEmpty())throw new IllegalArgumentException(String.join(",",errors));
                    return spec;
                }).onErrorResume(error->{
                    if(isDeadline(r))return Mono.error(error);
                    r.strategy="DETERMINISTIC_SPEC";r.warnings.add("PLANNER_FALLBACK");emit(r,"PLAN_FALLBACK",Map.of("reason","PLANNER_FAILED_OR_INVALID"));
                    return Mono.just(deterministic);
                });
    }
    private Mono<Snapshot> retrieve(Run r) {
        return fetchPools(r,r.spec.tasks(),5).map(pools->sanitize(r,pools,false)).flatMap(initial->{
            if(r.range.repeatPolicy()!=RepeatPolicy.HARD||validator.maximumMatching(r.spec,initial).size()==r.spec.tasks().size())return Mono.just(initial);
            List<Task> deficient=deficientTasks(r.spec,initial);
            if(deficient.isEmpty())return Mono.just(initial);
            return fetchPools(r,deficient,10).map(expanded->{
                Map<String,Pool> merged=new LinkedHashMap<>(initial.pools());
                expanded.forEach((task,pool)->{
                    if(pool.failed())return;
                    LinkedHashMap<Long,Candidate> union=new LinkedHashMap<>();initial.pools().get(task).candidates().forEach(c->union.put(c.mealId(),c));pool.candidates().forEach(c->union.put(c.mealId(),c));
                    merged.put(task,new Pool(union.values().stream().limit(10).toList(),false,pool.truncated()||union.size()>10));
                });
                r.warnings.add("CANDIDATES_EXPANDED");return sanitize(r,merged,true);
            });
        }).doOnNext(snapshot->emit(r,"PLAN_CANDIDATES_FETCHED",Map.of("candidateCount",snapshot.pools().values().stream().mapToInt(p->p.candidates().size()).sum(),"expanded",snapshot.expanded(),"truncated",snapshot.truncated())));
    }
    private Mono<Map<String,Pool>> fetchPools(Run r,List<Task> tasks,int limit) {
        return Flux.fromIterable(tasks).flatMap(task->bounded(r,budget.retrieval(),()->candidates.fetch(r.input,r.context,task,limit,allowance(r,budget.retrieval())))
                .onErrorResume(error->isDeadline(r)?Mono.error(error):Mono.just(new Pool(List.of(),true,false)))
                .doOnNext(pool->emit(r,"PLAN_TASK_CANDIDATES_FETCHED",Map.of("taskId",task.taskId(),"candidateCount",pool.candidates().size(),"failed",pool.failed(),"truncated",pool.truncated())))
                .map(pool->Map.entry(task.taskId(),pool)),budget.concurrency())
                .collectMap(Map.Entry::getKey,Map.Entry::getValue,LinkedHashMap::new);
    }
    private Snapshot sanitize(Run r,Map<String,Pool> raw,boolean expanded) {
        Map<Long,String> fingerprints=new HashMap<>();Set<Long> conflicts=new HashSet<>();
        for(Pool p:raw.values())for(Candidate c:p.candidates()) {
            String prior=fingerprints.putIfAbsent(c.mealId(),c.fingerprint());if(prior!=null&&!Objects.equals(prior,c.fingerprint()))conflicts.add(c.mealId());
        }
        Map<String,Pool> pools=new LinkedHashMap<>();Set<Long> global=new HashSet<>();int chars=0;boolean truncated=false;
        for(Task task:r.spec.tasks()) {
            Pool source=raw.getOrDefault(task.taskId(),new Pool(List.of(),true,false));List<Candidate> accepted=new ArrayList<>();Set<Long> local=new HashSet<>();boolean cut=source.truncated();
            if(!source.failed())for(Candidate c:source.candidates()) {
                if(conflicts.contains(c.mealId())||!validator.eligible(r.input,r.context,task,c)||!local.add(c.mealId()))continue;
                if(accepted.size()>=(expanded?10:5)){cut=true;continue;}
                if(!global.contains(c.mealId())) {
                    int length=json.tree(modelCandidate(c)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(global.size()>=60||chars+length>32000){cut=true;continue;}
                    global.add(c.mealId());chars+=length;
                }
                accepted.add(c);
            }
            truncated|=cut;pools.put(task.taskId(),new Pool(accepted,source.failed(),cut));
        }
        if(truncated&&!r.warnings.contains("CANDIDATES_TRUNCATED"))r.warnings.add("CANDIDATES_TRUNCATED");
        if(!conflicts.isEmpty())r.warnings.add("CANDIDATE_SNAPSHOT_CONFLICT");
        return new Snapshot(pools,expanded,truncated);
    }
    private List<Task> deficientTasks(Spec spec,Snapshot snapshot) {
        Map<String,Long> matching=validator.maximumMatching(spec,snapshot);Set<String> affected=new HashSet<>();
        for(Task task:spec.tasks())if(!matching.containsKey(task.taskId())&&!snapshot.pools().get(task.taskId()).failed())affected.add(task.taskId());
        boolean changed;
        do {
            changed=false;Set<Long> neighborhood=new HashSet<>();for(String task:affected)snapshot.pools().get(task).candidates().forEach(c->neighborhood.add(c.mealId()));
            for(var entry:matching.entrySet())if(neighborhood.contains(entry.getValue())&&affected.add(entry.getKey()))changed=true;
        }while(changed);
        return spec.tasks().stream().filter(t->affected.contains(t.taskId())).toList();
    }
    private Mono<Draft> compose(Run r,boolean repair) {
        Map<String,Object> payload=payload(r,r.spec);
        if(r.validation!=null){payload.put("draft",new Draft(r.validation.assignments(),r.validation.gaps()));payload.put("validationErrors",r.validation.errors());}
        payload.put("reviewIssues",r.unresolved);
        return invoke(r,Role.MENU,payload,budget.menu()).map(json::draft).map(draft->{
            Validation validation=validate(r,draft);
            if(!validation.valid())throw new IllegalArgumentException("INVALID_MENU:"+String.join(",",validation.errors()));
            emit(r,repair?"PLAN_REPAIRED":"PLAN_DRAFT_CREATED",Map.of());return draft;
        }).onErrorResume(error->{
            if(isDeadline(r))return Mono.error(error);
            r.strategy="DETERMINISTIC_MENU";r.warnings.add(repair?"MENU_REPAIR_FALLBACK":"MENU_FALLBACK");
            emit(r,"PLAN_FALLBACK",Map.of("reason",repair?"MENU_REPAIR_FAILED_OR_INVALID":"MENU_FAILED_OR_INVALID"));
            return Mono.just(validator.deterministic(r.spec,r.snapshot,r.range.repeatPolicy()));
        });
    }
    private Validation validate(Run r,Draft draft) {
        Validation result=validator.validate(r.input,r.context,r.range,r.spec,r.snapshot,draft);
        emit(r,"PLAN_VALIDATED",Map.of("validationErrors",result.errors(),"gapCount",result.gaps().size()));return result;
    }
    private Mono<Void> reviewAndRepair(Run r) {
        return review(r).flatMap(first->{
            if(first.decision()==ReviewDecision.PASS){r.reviewed=true;r.unresolved=List.of();return Mono.empty();}
            r.unresolved=first.issues();r.reviewed=true;
            if(first.decision()==ReviewDecision.NEEDS_INPUT||first.issues().stream().anyMatch(i->i.severity()==Severity.HARD)){r.needsInput=true;return Mono.empty();}
            // Reserve enough time for both repair calls and the final version checks.
            long reserve=budget.menu().plus(budget.review()).plusMillis(100).toNanos();
            if(remaining(r).toNanos()<reserve){r.warnings.add("REPAIR_BUDGET_INSUFFICIENT");return Mono.empty();}
            return compose(r,true).flatMap(draft->{r.validation=validate(r,draft);return review(r);}).doOnNext(second->{
                r.reviewed=true;
                if(second.decision()==ReviewDecision.PASS)r.unresolved=List.of();else r.unresolved=second.issues();
                if(second.decision()==ReviewDecision.NEEDS_INPUT||second.issues().stream().anyMatch(i->i.severity()==Severity.HARD))r.needsInput=true;
            }).onErrorResume(error->{if(isDeadline(r))return Mono.error(error);r.reviewed=false;r.warnings.add("REVIEW_UNAVAILABLE");return Mono.empty();}).then();
        }).onErrorResume(error->{if(isDeadline(r))return Mono.error(error);r.reviewed=false;r.warnings.add("REVIEW_UNAVAILABLE");return Mono.empty();});
    }
    private Mono<Review> review(Run r) {
        Map<String,Object> payload=payload(r,r.spec);payload.put("draft",new Draft(r.validation.assignments(),r.validation.gaps()));payload.put("validationErrors",r.validation.errors());
        return invoke(r,Role.REVIEW,payload,budget.review()).map(json::review).map(report->{
            Set<String> taskIds=new HashSet<>();r.spec.tasks().forEach(t->taskIds.add(t.taskId()));
            Set<String> constraints=new HashSet<>();var hard=r.context.hardConstraints();
            if(r.range.repeatPolicy()!=RepeatPolicy.ALLOW)constraints.add("noRepeat");
            if(!hard.requiredIngredients().isEmpty())constraints.add("requiredIngredients");
            if(!hard.excludedIngredients().isEmpty())constraints.add("excludedIngredients");
            if(!hard.requiredSlots().isEmpty())constraints.add("requiredSlots");
            if(!hard.excludedSlots().isEmpty())constraints.add("excludedSlots");
            r.context.preferences().forEach(p->constraints.add(p.preferenceId()));
            Set<String> evidence=new HashSet<>();r.snapshot.pools().values().forEach(p->p.candidates().forEach(c->evidence.addAll(c.facts().keySet())));
            constraints.forEach(c->evidence.add("constraint:"+c));r.context.preferences().forEach(p->evidence.add("preference:"+p.preferenceId()));
            Set<String> issueIds=new HashSet<>();
            for(Issue issue:report.issues()) {
                if(!issueIds.add(issue.issueId())||!taskIds.containsAll(issue.taskIds())||!constraints.contains(issue.constraintId())||issue.evidenceRefs().isEmpty()||!evidence.containsAll(issue.evidenceRefs()))throw new IllegalArgumentException("INVALID_REVIEW_REFERENCE");
                for(String ref:issue.evidenceRefs())if(ref.startsWith("meal:")&&issue.taskIds().stream().noneMatch(id->r.snapshot.pools().get(id).candidates().stream().anyMatch(c->c.facts().containsKey(ref))))throw new IllegalArgumentException("REVIEW_TASK_EVIDENCE_SCOPE");
                for(Preference pref:r.context.preferences())if(pref.preferenceId().equals(issue.constraintId())&&!pref.taskIds().isEmpty()&&!pref.taskIds().containsAll(issue.taskIds()))throw new IllegalArgumentException("REVIEW_PREFERENCE_SCOPE");
            }
            emit(r,"PLAN_REVIEWED",Map.of("decision",report.decision().name(),"issueCount",report.issues().size()));return report;
        });
    }
    private Mono<Result> finish(Run r) {
        if(r.needsInput) {
            List<String> reasons=new ArrayList<>(List.of("REVIEW_NEEDS_INPUT"));r.unresolved.forEach(i->reasons.add(i.code()+": "+i.suggestion()));
            return Mono.just(empty(r,Status.NEEDS_INPUT,reasons));
        }
        return bounded(r,budget.context(),()->contexts.unchanged(r.input,r.context,allowance(r,budget.context())))
                .flatMap(memoryOk->{if(!memoryOk)return Mono.just(false);return bounded(r,budget.retrieval(),()->candidates.unchanged(r.input,r.context,r.snapshot,allowance(r,budget.retrieval())));})
                .flatMap(unchanged->{
                    if(!unchanged)return Mono.just(empty(r,Status.NEEDS_INPUT,List.of("CONTEXT_CHANGED")));
                    Result result=render(r);
                    return bounded(r,budget.context(),()->repository.save(r.input,r.context,r.spec,r.snapshot,result)).map(result::identified)
                            .doOnNext(saved->emit(r,"PLAN_FINALIZED",Map.of("status",saved.status().name(),"planId",saved.planId(),"version",saved.version())));
                });
    }
    private Result render(Run r) {
        Map<Integer,List<Block>> byDay=new TreeMap<>();Set<Long> seen=new HashSet<>();List<Gap> gaps=new ArrayList<>(r.validation.gaps());
        for(Issue issue:r.unresolved)for(String task:issue.taskIds())if(gaps.stream().noneMatch(g->g.taskId().equals(task)&&g.code().equals("REVIEW_UNRESOLVED")))gaps.add(new Gap(task,"REVIEW_UNRESOLVED"));
        for(Task task:r.spec.tasks()) {
            Assignment a=r.validation.assignments().stream().filter(x->x.taskId().equals(task.taskId())).findFirst().orElseThrow();
            Candidate selected=a.mealId()==null?null:r.snapshot.pools().get(task.taskId()).candidates().stream().filter(c->c.mealId()==a.mealId()).findFirst().orElseThrow();
            boolean repeated=selected!=null&&!seen.add(selected.mealId());String reason="";
            if(selected!=null) {
                Candidate meal=selected;List<String> labels=a.reasonFactRefs().stream().map(meal.facts()::get).distinct().toList();
                reason=labels.isEmpty()?"已核验餐次和本轮硬条件":("餐食记录标签/食材："+String.join("、",labels));
                if(repeated)reason+="；候选有限，此餐与计划内其他餐次重复";
            }
            byDay.computeIfAbsent(task.dayOffset(),k->new ArrayList<>()).add(new Block(task.taskId(),task.mealTime(),selected,reason,repeated));
        }
        List<Day> days=byDay.entrySet().stream().map(e->new Day(r.range.startDate().plusDays(e.getKey()),e.getValue())).toList();
        Status status=!gaps.isEmpty()?Status.PARTIAL:r.reviewed?Status.COMPLETE:Status.DEGRADED;
        return new Result(null,0,status,r.strategy,r.reviewed,days,gaps,r.context.unsupportedConstraints(),List.copyOf(new LinkedHashSet<>(r.warnings)));
    }
    private Map<String,Object> payload(Run r,Spec spec) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("originalMessage",r.input.message());
        data.put("anchorDate",LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString());
        data.put("range",Map.of("startDate",r.range.startDate().toString(),"days",r.range.days(),"mealTimes",r.range.mealTimes(),"repeatPolicy",r.range.repeatPolicy().name()));
        data.put("repeatPolicy",r.range.repeatPolicy().name());data.put("hardConstraints",r.context.hardConstraints());data.put("preferences",r.context.preferences());
        data.put("capabilities",List.of("TAGS","INGREDIENTS"));data.put("tasks",spec.tasks());
        if(r.snapshot!=null) {
            Map<String,List<Long>> membership=new LinkedHashMap<>();Map<Long,Map<String,Object>> facts=new LinkedHashMap<>();
            r.snapshot.pools().forEach((task,pool)->{membership.put(task,pool.candidates().stream().map(Candidate::mealId).toList());pool.candidates().forEach(c->facts.putIfAbsent(c.mealId(),modelCandidate(c)));});
            data.put("candidatePools",membership);data.put("candidateFacts",facts.values());
            Map<String,String> status=new LinkedHashMap<>();r.snapshot.pools().forEach((task,pool)->status.put(task,pool.failed()?"RETRIEVAL_FAILED":pool.candidates().isEmpty()?"NO_MATCH":"READY"));
            data.put("taskRetrievalStatus",status);
        }
        return data;
    }
    private Map<String,Object> modelCandidate(Candidate c) {
        return Map.of("mealId",c.mealId(),"name",c.name(),"mealTimes",c.mealTimes(),"ingredients",c.ingredients(),"ingredientsKnown",c.ingredientsKnown(),"tags",c.tags(),"facts",c.facts());
    }
    private Mono<String> invoke(Run r,Role role,Map<String,Object> payload,Duration limit) {
        return Mono.defer(()->{
            if(r.calls>=5)return Mono.error(new IllegalStateException("MODEL_CALL_LIMIT"));
            Duration allowed=allowance(r,limit);r.calls++;
            JsonNode tree=json.tree(payload);if(tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>48000)return Mono.error(new IllegalArgumentException("MODEL_INPUT_BUDGET"));
            return bounded(r,allowed,()->roles.call(role,tree,allowed)).switchIfEmpty(Mono.error(new IllegalStateException("EMPTY_ROLE_OUTPUT")));
        });
    }
    private <T> Mono<T> bounded(Run r,Duration stage,Supplier<Mono<T>> action) {
        return Mono.defer(()->{Duration allowed=allowance(r,stage);return action.get().timeout(allowed).switchIfEmpty(Mono.error(new IllegalStateException("EMPTY_PORT_OUTPUT")));});
    }
    private Duration allowance(Run r,Duration cap) {Duration left=remaining(r);if(left.isZero()||left.isNegative())throw new IllegalStateException("PLANNING_DEADLINE");return left.compareTo(cap)<0?left:cap;}
    private Duration remaining(Run r) {return Duration.ofNanos(r.deadline-System.nanoTime());}
    private boolean isDeadline(Run r) {return remaining(r).isNegative()||remaining(r).isZero();}
    private Result empty(Run r,Status status,List<String> reasons) {
        List<String> warnings=new ArrayList<>(r.warnings);warnings.addAll(reasons);
        return new Result(null,0,status,r.strategy,false,List.of(),List.of(),r.context==null?List.of():r.context.unsupportedConstraints(),List.copyOf(new LinkedHashSet<>(warnings)));
    }
    private void emit(Run r,String name,Map<String,Object> details) {
        Map<String,Object> data=new LinkedHashMap<>(details);data.put("planRunId",r.runId);data.put("stage",name);data.put("attempt",r.calls);data.put("strategy",r.strategy);
        data.put("promptVersion","015-v1");data.put("schemaVersion","015-v1");data.put("elapsedMs",(System.nanoTime()-r.started)/1000000);data.put("usage",null);
        try{events.emit(r.input,name,Collections.unmodifiableMap(data));}catch(RuntimeException ignored){/* Observability cannot change authority. */}
    }
    private final class Run {
        final Input input;final Range range;final String runId=UUID.randomUUID().toString();final long started=System.nanoTime();final long deadline=started+budget.total().toNanos();
        final List<String> warnings=new ArrayList<>();FrozenContext context;Spec spec;Snapshot snapshot;Validation validation;List<Issue> unresolved=List.of();
        String strategy="MULTI_AGENT";boolean reviewed,needsInput;int calls;
        Run(Input input,Range range){this.input=input;this.range=range;}
    }
}
