package com.diet.service.trace;

import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTracePropagationTest {
    @Test
    void concurrentWorkersRecordEveryEventOnceInTheOwningTrace() throws Exception {
        var rows = new ArrayList<RequestTraceRow>();
        var trace = recorder(rows);
        var executor = Executors.newFixedThreadPool(4);
        var started = new CountDownLatch(4);
        try (var scope = trace.openTrace("owner", "s", 7L)) {
            var work = trace.propagate(() -> {
                started.countDown();
                try {
                    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                for (int i = 0; i < 100; i++) trace.recordEvent("WORK", "TEST", null, i);
                return "done";
            });
            List<Future<String>> completed = new ArrayList<>();
            for (int i = 0; i < 4; i++) completed.add(executor.submit(work::get));
            for (var result : completed) assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(rows).isEmpty();
        } finally {
            executor.shutdownNow();
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventCount()).isEqualTo(400);
        var events = new ObjectMapper().readTree(rows.get(0).getTraceJson()).path("events");
        List<Integer> orders = new ArrayList<>();
        events.forEach(event -> orders.add(event.path("stepOrder").asInt()));
        assertThat(orders).doesNotHaveDuplicates().contains(1, 400).isSorted();
    }

    @Test
    void workerBindingRestoresExistingScopeAfterFailureAndDoesNotFlushOwner() throws Exception {
        var rows = new ArrayList<RequestTraceRow>();
        var trace = recorder(rows);
        var executor = Executors.newSingleThreadExecutor();
        try (var owner = trace.openTrace("owner", "s", 7L)) {
            var failing = trace.propagate(() -> {
                trace.recordError("WORK_FAILED", "TEST", null, new IllegalStateException("controlled"));
                throw new IllegalStateException("controlled");
            });
            executor.submit(() -> {
                try (var worker = trace.openTrace("worker", "w", 8L)) {
                    assertThatThrownBy(failing::get).isInstanceOf(IllegalStateException.class);
                    trace.recordEvent("RESTORED", "TEST", null, null);
                }
                // A reused worker must carry no request scope after closing its own scope.
                trace.recordEvent("LEAK", "TEST", null, null);
            }).get(5, TimeUnit.SECONDS);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTraceId()).isEqualTo("worker");
        } finally {
            executor.shutdownNow();
        }
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getTraceJson()).contains("RESTORED").doesNotContain("WORK_FAILED", "LEAK");
        assertThat(rows.get(1).getTraceId()).isEqualTo("owner");
        assertThat(rows.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(rows.get(1).getEventCount()).isEqualTo(1);
        assertThat(rows.get(1).getTraceJson()).contains("WORK_FAILED").doesNotContain("RESTORED", "LEAK");
    }

    @Test
    void emptyWorkerScopeIsCleanedInFinallyAfterSuccessfulPropagation() throws Exception {
        var rows = new ArrayList<RequestTraceRow>();
        var trace = recorder(rows);
        var executor = Executors.newSingleThreadExecutor();
        try (var owner = trace.openTrace("owner", "s", 7L)) {
            var work = trace.propagate(() -> {
                trace.recordEvent("BOUND", "TEST", null, null);
                return true;
            });
            executor.submit(work::get).get(5, TimeUnit.SECONDS);
            executor.submit(() -> trace.recordEvent("LEAK", "TEST", null, null)).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventCount()).isEqualTo(1);
        assertThat(rows.get(0).getTraceJson()).contains("BOUND").doesNotContain("LEAK");
    }

    private AgentTraceService recorder(List<RequestTraceRow> rows) {
        var mapper = mock(AgentTraceMapper.class);
        doAnswer(call -> { rows.add(call.getArgument(0)); return 1; }).when(mapper).insert(any());
        return new AgentTraceService(mapper, new ObjectMapper());
    }
}
