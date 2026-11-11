package com.diet.auth;

import com.diet.controller.admin.AdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@ContextConfiguration(classes = SecurityRoleTest.TestApp.class)
class SecurityRoleTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfiguration.class, AdminController.class})
    static class TestApp {}
    @Autowired MockMvc mvc;
    @MockBean AuthService auth;
    @MockBean AccountService accounts;

    @Test void ordinaryUserCannotReadAdminEndpoints() throws Exception {
        when(auth.authenticate("user-token")).thenReturn(new AuthPrincipal(1_000_001L, "USER", "session"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }
}
