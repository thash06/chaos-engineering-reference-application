package com.company.subdomain.resilience.refapp.service;

import com.company.subdomain.resilience.refapp.exception.ChaosEngineeringRuntimeException;
import com.company.subdomain.resilience.refapp.exception.TemporaryServiceOutageException;
import com.company.subdomain.resilience.refapp.util.YMLConfig;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
class PatternsFactory {
    private static Logger LOGGER = LoggerFactory.getLogger(PatternsFactory.class);

    static final String TIME_LIMITER = "time-limiter";
    static final String SEMAPHORE_BULKHEAD = "semaphore-bulkhead";
    static final String THREAD_POOL_BULKHEAD = "thread-pool-bulkhead";
    static final String RETRY_SERVICE = "retry-for-bulkhead";
    static final String CIRCUIT_BREAKER = "circuit-breaker";
    static final String RATE_LIMITER = "rate-limiter";

    final ThreadPoolBulkhead threadPoolBulkhead;
    final Bulkhead bulkhead;
    final Retry retry;
    final TimeLimiter timeLimiter;
    final CircuitBreaker circuitBreaker;
    final RateLimiter rateLimiter;

    public PatternsFactory(YMLConfig ymlConfig) {
        this.threadPoolBulkhead = createThreadPoolBulkhead(ymlConfig.getNumberOfThreads());
        this.bulkhead = createBulkhead(ymlConfig.getNumberOfThreads());
        retry = createRetry(ymlConfig.getInitialIntervalMillis(), ymlConfig.getMultiplier(), ymlConfig.getMaxAttempts());
        timeLimiter = createTimeLimiter(ymlConfig.getWaitTimeDuration());
        circuitBreaker = createCircuitBreaker(ymlConfig.getFailureRateThreshold(), ymlConfig.getWaitDurationInOpenState(),
                ymlConfig.getPermittedNumberOfCallsInHalfOpenState(), ymlConfig.getSlidingWindowSize());
        this.rateLimiter = createRateLimiter(ymlConfig.getLimitForPeriod(), ymlConfig.getWindowInMilliseconds(),
                ymlConfig.getWaitTimeForThread());

    }

    private Retry createRetry(int initialIntervalMillis, int multiplier, int maxAttempts) {
        IntervalFunction intervalWithCustomExponentialBackoff = IntervalFunction
                .ofExponentialBackoff(initialIntervalMillis, multiplier);
        RetryConfig retryConfig = RetryConfig.custom()
                .intervalFunction(intervalWithCustomExponentialBackoff)
                .maxAttempts(maxAttempts)
                .retryExceptions(TemporaryServiceOutageException.class, ChaosEngineeringRuntimeException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        return retryRegistry.retry(RETRY_SERVICE);
    }

    private TimeLimiter createTimeLimiter(int waitTimeForThread) {
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .cancelRunningFuture(true)
                .timeoutDuration(Duration.ofMillis(waitTimeForThread))
                .build();
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(timeLimiterConfig);
        return timeLimiterRegistry.timeLimiter(TIME_LIMITER);
    }

    private ThreadPoolBulkhead createThreadPoolBulkhead(int availableProcessors) {
        int coreThreadPoolSizeFactor = availableProcessors >= 8 ? 4 : 1;
        int coreThreadPoolSize = availableProcessors - coreThreadPoolSizeFactor;
        ThreadPoolBulkheadConfig threadPoolBulkheadConfig = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(availableProcessors)
                .coreThreadPoolSize(coreThreadPoolSize)
                .queueCapacity(4)
                .keepAliveDuration(Duration.ofSeconds(2))
                .build();
        LOGGER.info("ThreadPoolBulkheadConfig created with maxThreadPoolSize {} : coreThreadPoolSize {}",
                availableProcessors, coreThreadPoolSize);
        ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry = ThreadPoolBulkheadRegistry.of(threadPoolBulkheadConfig);
        return threadPoolBulkheadRegistry.bulkhead(THREAD_POOL_BULKHEAD);
    }

    private Bulkhead createBulkhead(int availableProcessors) {
        LOGGER.info("Semaphore bulkhead with maxConcurrentCalls {}", availableProcessors);
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(availableProcessors)
                .maxWaitDuration(Duration.ofMillis(0))
                .writableStackTraceEnabled(true)
                .build();

        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);
        return bulkheadRegistry.bulkhead(SEMAPHORE_BULKHEAD);
    }

    private CircuitBreaker createCircuitBreaker(int failureRateThreshold, int waitDurationInOpenState,
                                                int permittedNumberOfCallsInHalfOpenState, int slidingWindowSize) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenState))
                .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                .slidingWindowSize(slidingWindowSize)
                .recordExceptions(ChaosEngineeringRuntimeException.class, TemporaryServiceOutageException.class)
                .ignoreExceptions(IOException.class)
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        return circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER);
    }

    private RateLimiter createRateLimiter(int limitForPeriod, int windowInMilliseconds, int waitTimeForThread) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMillis(windowInMilliseconds))
                .limitForPeriod(limitForPeriod)
                .timeoutDuration(Duration.ofMillis(waitTimeForThread))
                .build();
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(rateLimiterConfig);
        return rateLimiterRegistry.rateLimiter(RATE_LIMITER);
    }
}
