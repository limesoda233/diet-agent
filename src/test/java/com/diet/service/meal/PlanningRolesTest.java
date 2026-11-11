package com.diet.service.meal;
import com.diet.service.meal.planning.runtime.AgentScopePlanningRoles;
import com.diet.service.meal.planning.PlanningTypes.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.*;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlanningRolesTest {
    @Test void rolesUseStatelessMessagesAndTimeoutInsteadOfSharingAgentMemory() {
        var model=new Model(){
            @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                if(!tools.isEmpty()||messages.size()!=2)throw new IllegalStateException("unexpected history or tools");
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("{\"decision\":\"PASS\",\"issues\":[]}").build())).build());
            }
            @Override public String getModelName(){return "fixture";}
        };
        var roles=new AgentScopePlanningRoles(model,model,null);
        assertEquals("{\"decision\":\"PASS\",\"issues\":[]}",roles.call(Role.REVIEW,new ObjectMapper().createObjectNode(),Duration.ofSeconds(1)).block());
        var hanging=new Model(){
            @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){return Flux.never();}
            @Override public String getModelName(){return "fixture";}
        };
        assertThrows(RuntimeException.class,()->new AgentScopePlanningRoles(hanging,hanging,null).call(Role.MENU,new ObjectMapper().createObjectNode(),Duration.ofMillis(20)).block(Duration.ofSeconds(1)));
    }
}
