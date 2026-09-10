package com.diet.evaluation.retrieval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MySqlEvaluationConfigTest {
    @Test
    void requiresADedicatedLocalDatabaseBeforeOpeningAnyConnection() {
        assertThatCode(()->MySqlRetrievalAdapter.validateUrl("jdbc:mysql://127.0.0.1:33077/diet_retrieval_eval?useSSL=false")).doesNotThrowAnyException();
        for(String url:new String[]{null,"","jdbc:mysql://localhost/diet_db","jdbc:mysql://remote.example/diet_retrieval_eval",
                "jdbc:mysql://localhost/diet_retrieval_eval?password=example","jdbc:mysql://user@localhost/diet_retrieval_eval"}) {
            assertThatThrownBy(()->MySqlRetrievalAdapter.validateUrl(url)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
