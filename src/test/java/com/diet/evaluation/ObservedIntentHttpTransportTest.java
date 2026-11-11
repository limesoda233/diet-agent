package com.diet.evaluation;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedIntentHttpTransportTest {
    @Test
    void publisherResubscriptionDoesNotInventAnotherTransportRequest() {
        var transport = new ObservedIntentHttpTransport(new HttpTransport() {
            @Override public HttpResponse execute(HttpRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flux<String> stream(HttpRequest request) {
                return Flux.just("response");
            }
            @Override public void close() {}
        });
        var stream = transport.stream(HttpRequest.builder().url("https://fixture.invalid").build());
        assertThat(transport.attempts()).isEqualTo(1);
        assertThat(stream.repeat(1).collectList().block()).containsExactly("response", "response");
        assertThat(transport.attempts()).isEqualTo(1);
    }

    @Test
    void separateExecuteAttemptsAreCountedEvenWhenFirstThrows() {
        var transport = new ObservedIntentHttpTransport(new HttpTransport() {
            int count;
            @Override public HttpResponse execute(HttpRequest request) {
                if (++count == 1) throw new IllegalStateException("temporary");
                return HttpResponse.builder().statusCode(200).body("response").build();
            }
            @Override public Flux<String> stream(HttpRequest request) { throw new UnsupportedOperationException(); }
            @Override public void close() {}
        });
        try { transport.execute(HttpRequest.builder().url("https://fixture.invalid").build()); }
        catch (IllegalStateException expected) { /* First attempt remains charged. */ }
        assertThat(transport.execute(HttpRequest.builder().url("https://fixture.invalid").build()).getBody()).isEqualTo("response");
        assertThat(transport.attempts()).isEqualTo(2);
    }

    @Test
    void delayedSubscriptionsRemainAttributedToOriginalSampleAfterReset() {
        var transport = new ObservedIntentHttpTransport(new HttpTransport() {
            @Override public HttpResponse execute(HttpRequest request) { throw new UnsupportedOperationException(); }
            @Override public Flux<String> stream(HttpRequest request) { return Flux.just("response"); }
            @Override public void close() {}
        });
        var old = transport.stream(HttpRequest.builder().url("https://fixture.invalid").build());
        transport.reset();
        old.collectList().block();
        assertThat(transport.attempts()).isZero();
        transport.stream(HttpRequest.builder().url("https://fixture.invalid").build()).collectList().block();
        assertThat(transport.attempts()).isEqualTo(1);
    }
}
