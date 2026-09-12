package com.diet.service.cooking;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;

class RecipeMcpIntegrationTest {
    @Test void discoversAndCallsAllToolsThroughARealChildProcessAndClosesIt() throws Exception {
        var before=ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        List<ProcessHandle> children;
        var properties=new CookingProperties();
        properties.setServerJar(System.getProperty("cooking.eval.jar",""));
        try(var client=new RecipeMcpClient(properties)) {
            assertThat(client.toolNames()).containsExactlyInAnyOrder("search_recipes","get_recipe","suggest_substitutions");
            assertThat(client.registeredToolNames()).containsExactlyInAnyOrder("search_recipes","get_recipe","suggest_substitutions");
            assertThat(client.call("search_recipes",Map.of("query","番茄炒蛋")).get("recipes")).asList().hasSize(1);
            assertThat(client.call("get_recipe",Map.of("recipeId","recipe_01"))).containsKey("recipe");
            assertThat(client.call("suggest_substitutions",Map.of("recipeId","recipe_08","ingredient","牛奶")).get("substitutions")).asList().hasSize(1);
            assertThatThrownBy(()->client.call("get_recipe",Map.of("recipeId","missing"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->client.call("delete_recipe",Map.of())).isInstanceOf(IllegalArgumentException.class);
            children=ProcessHandle.current().descendants().filter(p->!before.contains(p.pid())).toList();
            assertThat(children).isNotEmpty();
            assertThat(client.ownedProcessIds()).hasSize(1).allMatch(pid->children.stream().anyMatch(p->p.pid()==pid));
        }
        for(int i=0;i<30 && children.stream().anyMatch(ProcessHandle::isAlive);i++) Thread.sleep(100);
        assertThat(children).noneMatch(ProcessHandle::isAlive);
    }
}
