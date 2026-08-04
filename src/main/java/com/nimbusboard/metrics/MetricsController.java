package com.nimbusboard.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom metrics endpoint supplementing Actuator's /actuator/prometheus.
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MeterRegistry meterRegistry;

    @GetMapping
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("jvm.memory.used", getGaugeValue("jvm.memory.used"));
        metrics.put("jvm.threads.live", getGaugeValue("jvm.threads.live"));
        metrics.put("http.server.requests.count",
                getCounterValue("http.server.requests"));
        return metrics;
    }

    private double getGaugeValue(String name) {
        var gauge = meterRegistry.find(name).gauge();
        return gauge != null ? gauge.value() : 0;
    }

    private double getCounterValue(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0;
    }
}
