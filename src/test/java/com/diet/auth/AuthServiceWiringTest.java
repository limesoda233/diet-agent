package com.diet.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceWiringTest {
    @Test
    void springCreatesAuthServiceWithItsDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("diet.auth.session-ttl", "PT168H")));
            context.registerBean(AuthRepository.class, () -> new AuthRepository(new JdbcTemplate()));
            context.registerBean(WechatCodeExchangeClient.class, () -> code -> "openid");
            context.registerBean(TransactionTemplate.class,
                    () -> new TransactionTemplate(new DataSourceTransactionManager()));
            context.registerBean(AuthService.class);

            context.refresh();

            assertThat(context.getBean(AuthService.class)).isNotNull();
        }
    }
}
