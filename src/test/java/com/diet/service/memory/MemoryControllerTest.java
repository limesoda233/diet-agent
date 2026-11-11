package com.diet.service.memory;

import com.diet.controller.memory.MemoryController;
import com.diet.auth.AuthService;
import com.diet.auth.BearerAuthenticationFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryControllerTest extends MemoryDatabaseFixture {
    @Test void managementRequiresOwnerAndCurrentVersionAndDoesNotTrustBodyUser() throws Exception {
        var j=store.appendTurn(1L,"s1","我对花生过敏","t1");
        var item=store.remember(j,"ALLERGEN","花生","NEUTRAL","EXPLICIT","我对花生过敏",false);
        var mvc=MockMvcBuilders.standaloneSetup(new MemoryController(store,true))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new BearerAuthenticationFilter(org.mockito.Mockito.mock(AuthService.class),true)).build();
        mvc.perform(get("/api/v1/diet/memories").header("X-User-Id","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("PENDING"));
        mvc.perform(patch("/api/v1/diet/memories/"+item.id()).header("X-User-Id","2").contentType("application/json")
                .content("{\"version\":1,\"action\":\"CONFIRM\"}" )).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/diet/memories/"+item.id()).header("X-User-Id","1").contentType("application/json")
                .content("{\"version\":1,\"action\":\"CONFIRM\",\"userId\":2}" )).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/diet/memories/"+item.id()).header("X-User-Id","1").contentType("application/json")
                .content("{\"version\":1,\"action\":\"CONFIRM\"}" )).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(patch("/api/v1/diet/memories/"+item.id()).header("X-User-Id","1").contentType("application/json")
                .content("{\"version\":1,\"action\":\"DELETE\"}" )).andExpect(status().isBadRequest());
    }
    @Test void disabledFeatureDoesNotTouchMissingTables() throws Exception {
        jdbc.execute("DROP TABLE user_memory");
        var mvc=MockMvcBuilders.standaloneSetup(new MemoryController(store,false))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new BearerAuthenticationFilter(org.mockito.Mockito.mock(AuthService.class),true)).build();
        mvc.perform(get("/api/v1/diet/memories").header("X-User-Id","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/v1/diet/memories/settings").header("X-User-Id","1").contentType("application/json").content("{\"collectionEnabled\":true}"))
                .andExpect(status().isBadRequest());
    }
}
