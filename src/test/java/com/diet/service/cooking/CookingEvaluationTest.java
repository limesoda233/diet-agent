package com.diet.service.cooking;

import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CookingEvaluationTest {
    record Task(String id,String message,Map<String,Object> context,String expectedAction,String expectedText,List<String> expectedIds,
                List<String> requiredIngredients,List<String> excludedIngredients,Map<String,List<String>> requiredSlots) {}
    @Test void evaluatesFixedTasksThroughARealMcpProcess() throws Exception {
        Path report=Path.of("target/evaluation/cooking/report.json"); Files.createDirectories(report.getParent()); Files.deleteIfExists(report);
        var json=new ObjectMapper();
        var tasks=json.readValue(Files.readString(Path.of("src/test/resources/evaluation/cooking/tasks.json")),new TypeReference<List<Task>>(){});
        assertThat(tasks).hasSizeGreaterThanOrEqualTo(24);
        assertThat(tasks.stream().map(Task::id).distinct().count()).isEqualTo(tasks.size());
        var props=new CookingProperties(); props.setEnabled(true);
        var rows=new ArrayList<Map<String,Object>>();
        int correct=0,violations=0; var totalCalls=new int[1]; var successfulCalls=new int[1];
        long initialization=System.nanoTime();
        try(var client=new RecipeMcpClient(props)) {
            client.toolNames(); double initMs=(System.nanoTime()-initialization)/1_000_000.0;
            var router=new CookingRouter();
            for(var task:tasks) {
                int before=totalCalls[0]; var returned=new ArrayList<Map<String,Object>>();
                RecipeProvider counted=(name,args)-> {
                    totalCalls[0]++;
                    var result=client.call(name,args); successfulCalls[0]++;
                    returned.add(Map.of("tool",name,"result",result)); return result;
                };
                var service=new CookingService(counted,props,mock(AgentTraceService.class));
                var constraints=new MealConstraints(task.requiredIngredients(),task.excludedIngredients(),task.requiredSlots(),null);
                String action="none",text=""; long start=System.nanoTime();
                try {
                    var request=router.route(task.message(),task.context());
                    if(request.isPresent()) { action=request.get().action(); text=service.respond(request.get(),constraints); }
                } catch(IllegalArgumentException expectedValidation) { action="reject"; text="reject"; }
                boolean routeCorrect=action.equals(task.expectedAction()); if(routeCorrect) correct++;
                assertThat(action).as(task.id()+" action").isEqualTo(task.expectedAction());
                assertThat(text).as(task.id()+" text").contains(task.expectedText());
                for(String id:task.expectedIds()) assertThat(text).as(task.id()+" recipe ID").contains(id);
                assertThat(totalCalls[0]-before).isLessThanOrEqualTo(3);
                // Check records actually selected for display, not rejected tool candidates.
                for(var call:returned) {
                    Map<String,Object> result=(Map<String,Object>)call.get("result");
                    List<Map<String,Object>> records=result.containsKey("recipe")?List.of((Map<String,Object>)result.get("recipe")):
                            (List<Map<String,Object>>)result.getOrDefault("recipes",result.getOrDefault("substitutions",List.of()));
                    for(var record:records) {
                        boolean shown=text.contains("["+record.get("recipeId")+"]") || record.containsKey("replacement") && text.contains(" → "+record.get("replacement"));
                        if(shown) {
                            var ingredients=(List<String>)record.getOrDefault("ingredients",record.get("resultingIngredients"));
                            if(!ingredients.containsAll(constraints.requiredIngredients()) || !Collections.disjoint(ingredients,constraints.excludedIngredients())) violations++;
                        }
                    }
                }
                rows.add(Map.of("id",task.id(),"actualAction",action,"routeCorrect",routeCorrect,"calls",totalCalls[0]-before,"response",text,"latencyMs",(System.nanoTime()-start)/1_000_000.0));
            }
            assertThat(violations).isZero();
            var result=new LinkedHashMap<String,Object>();
            result.put("runAt",java.time.OffsetDateTime.now().toString()); result.put("transport","real child process stdio via AgentScope 1.0.11");
            result.put("dataStatus","LOCAL_EXAMPLE, unverified"); result.put("routing","finite grammar and structured requests; no LLM evaluation");
            result.put("taskCount",tasks.size()); result.put("routeAccuracy",correct/(double)tasks.size());
            result.put("toolCalls",totalCalls[0]); result.put("successfulToolCalls",successfulCalls[0]); result.put("toolSuccessRate",successfulCalls[0]/(double)totalCalls[0]);
            result.put("toolErrorNote","Expected invalid IDs and excluded ingredients count as tool errors; not transport failure");
            result.put("constraintViolations",violations); result.put("initializationMs",initMs); result.put("rows",rows);
            var latencies=rows.stream().mapToDouble(row->((Number)row.get("latencyMs")).doubleValue()).sorted().toArray();
            result.put("latencyP50Ms",latencies[(int)Math.ceil(latencies.length*0.50)-1]);
            result.put("latencyP95Ms",latencies[(int)Math.ceil(latencies.length*0.95)-1]);
            var hashes=new LinkedHashMap<String,String>();
            for(String path:List.of("src/test/resources/evaluation/cooking/tasks.json","src/main/resources/diet/cooking/recipes.json","src/main/resources/diet/cooking/substitutions.json"))
                hashes.put(path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
            result.put("sha256",hashes); json.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),result);
        }
    }
}
