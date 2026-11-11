package com.diet.service.meal;
import com.diet.service.trace.AgentTraceService;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Mono;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class PlanningTraceTest {
    @Test void capturedTraceCarriesLaterCreatedStageCallbacksAcrossWorkers() throws Exception {
        var mapper=mock(AgentTraceMapper.class);var json=new ObjectMapper();var trace=new AgentTraceService(mapper,json);
        try(var scope=trace.openTrace("trace-plan","s1",1L)) {
            var context=trace.captureContext();
            Mono.fromCallable(()->context.call(()->{trace.recordEvent("PLAN_REVIEWED","PLAN",null,Map.of("reviewed",true));return true;})).subscribeOn(Schedulers.boundedElastic()).block();
        }
        var row=ArgumentCaptor.forClass(RequestTraceRow.class);verify(mapper).insert(row.capture());
        assertEquals("PLAN_REVIEWED",json.readTree(row.getValue().getTraceJson()).path("events").get(0).path("eventType").asText());
    }
}
