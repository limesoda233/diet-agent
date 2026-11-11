package com.diet.evaluation;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Passive observation only: preserve the SDK's eager/cold behavior and its existing retry policy. */
final class ObservedIntentHttpTransport implements HttpTransport {
    private final HttpTransport delegate;
    private final ObjectMapper json = new ObjectMapper();
    private volatile Sample sample = new Sample();
    ObservedIntentHttpTransport(HttpTransport delegate) { this.delegate = delegate; }
    void reset() { sample = new Sample(); }
    int attempts() { return sample.count.get(); }
    boolean completeUsagePresent() { return sample.usage.get(); }
    @Override public HttpResponse execute(HttpRequest request) {
        Sample captured = sample; captured.count.incrementAndGet();
        HttpResponse response = delegate.execute(request);
        observeUsage(response.getBody(), captured); return response;
    }
    @Override public Flux<String> stream(HttpRequest request) {
        // Default SDK transport starts the HTTP request here. Resubscribing its existing Flux is not a new request.
        Sample captured = sample; captured.count.incrementAndGet();
        return delegate.stream(request).doOnNext(line -> observeUsage(line, captured));
    }
    @Override public void close() { delegate.close(); }
    private void observeUsage(String body, Sample captured) {
        if (body == null) return;
        String value = body.strip();
        if (value.startsWith("data:")) value = value.substring(5).strip();
        if (!value.startsWith("{")) return;
        try {
            var usage = json.readTree(value).path("usage");
            var input = usage.path("prompt_tokens"); var output = usage.path("completion_tokens");
            if (input.isIntegralNumber() && input.canConvertToLong() && input.longValue() >= 0
                    && output.isIntegralNumber() && output.canConvertToLong() && output.longValue() >= 0)
                captured.usage.set(true);
        } catch (Exception ignored) { /* Observation must never change provider parsing or its error behavior. */ }
    }
    private static final class Sample {
        final AtomicInteger count = new AtomicInteger();
        final AtomicBoolean usage = new AtomicBoolean();
    }
}
