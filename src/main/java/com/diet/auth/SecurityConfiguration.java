package com.diet.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;

@Configuration
public class SecurityConfiguration {
    @Bean
    BearerAuthenticationFilter bearerAuthenticationFilter(AuthService auth, Environment environment,
            @Value("${diet.wechat.app-secret:}") String appSecret,
            @Value("${diet.auth.bootstrap-invite-code:}") String bootstrapInvite) {
        boolean localAuth = Arrays.asList(environment.getActiveProfiles()).contains("local-auth");
        boolean hosted = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("experience") || profile.equals("prod"));
        if (localAuth && hosted) {
            throw new IllegalStateException("local-auth cannot run with experience or prod");
        }
        if (hosted && appSecret.isBlank()) {
            throw new IllegalStateException("WECHAT_MINI_APP_SECRET is required");
        }
        if (hosted && bootstrapInvite.isBlank()) {
            throw new IllegalStateException("DIET_BOOTSTRAP_INVITE_CODE is required");
        }
        return new BearerAuthenticationFilter(auth, localAuth);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerAuthenticationFilter bearer)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> SecurityErrorWriter.write(
                                response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, error) -> SecurityErrorWriter.write(
                                response, 403, "FORBIDDEN", "无权访问")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/auth/wechat/login").permitAll()
                        .requestMatchers("/api/v1/diet/debug/**", "/api/v1/diet/evaluations/**",
                                "/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
