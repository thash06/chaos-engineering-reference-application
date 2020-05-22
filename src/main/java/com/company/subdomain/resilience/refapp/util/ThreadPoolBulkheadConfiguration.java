package com.company.subdomain.resilience.refapp.util;

import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ThreadPoolBulkheadConfiguration {
    @Bean
    public ThreadPoolBulkheadRegistry retrieveThreadPoolBulkheadRegistry() {
        ThreadPoolBulkheadRegistry registry =
                ThreadPoolBulkheadRegistry.of(bulkheadThreadPoolDefaultConfiguration());
// Add custom circuit breakers as well
        registry.addConfiguration("customConfiguration",
                bulkheadThreadPoolCustomConfiguration());
        return registry;
    }

    private ThreadPoolBulkheadConfig bulkheadThreadPoolDefaultConfiguration() {
        ThreadPoolBulkheadConfig threadPoolBulkheadConfig =
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(2)
                        .coreThreadPoolSize(2)
                        .queueCapacity(1)
                        .build();
        return threadPoolBulkheadConfig;
    }

    private ThreadPoolBulkheadConfig bulkheadThreadPoolCustomConfiguration() {
        ThreadPoolBulkheadConfig threadPoolBulkheadConfig =
                ThreadPoolBulkheadConfig.custom()
                        .maxThreadPoolSize(5)
                        .coreThreadPoolSize(1)
                        .queueCapacity(1)
                        .build();
        return threadPoolBulkheadConfig;
    }
}
