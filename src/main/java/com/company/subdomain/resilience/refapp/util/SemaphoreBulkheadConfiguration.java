package com.company.subdomain.resilience.refapp.util;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SemaphoreBulkheadConfiguration {

    @Bean
    public BulkheadRegistry retrieveBulkheadRegistry() {
        BulkheadRegistry registry = BulkheadRegistry.of
                (bulkheadSemaphoreDefaultConfiguration());
        registry.addConfiguration("customConfiguration",
                bulkheadSemaphoreCustomConfiguration());
        return registry;
    }

    private BulkheadConfig bulkheadSemaphoreDefaultConfiguration() {
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ofSeconds(1))
                .build();
        return bulkheadConfig;
    }

    private BulkheadConfig bulkheadSemaphoreCustomConfiguration() {
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(5)
                .maxWaitDuration(Duration.ofSeconds(1))
                .build();
        return bulkheadConfig;
    }
}
