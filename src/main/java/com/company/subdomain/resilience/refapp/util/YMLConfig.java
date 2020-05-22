package com.company.subdomain.resilience.refapp.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "patterns")
public class YMLConfig {
    @Value("${patterns.config.retry.initialIntervalMillis}")
    private int initialIntervalMillis;
    @Value("${patterns.config.retry.multiplier}")
    private int multiplier;
    @Value("${patterns.config.retry.maxAttempts}")
    private int maxAttempts;
    @Value("${patterns.config.timeLimiter.waitTimeDuration}")
    private int waitTimeDuration;
    @Value("${patterns.config.circuitBreaker.failureRateThreshold}")
    private int failureRateThreshold;
    @Value("${patterns.config.circuitBreaker.waitTimeDuration}")
    private int waitDurationInOpenState;
    @Value("${patterns.config.circuitBreaker.permittedNumberOfCallsInHalfOpenState}")
    private int permittedNumberOfCallsInHalfOpenState;
    @Value("${patterns.config.circuitBreaker.slidingWindowSize}")
    private int slidingWindowSize;
    @Value("${patterns.config.bulkhead.numberOfThreads}")
    private int numberOfThreads;
    @Value("${patterns.config.rateLimiter.limitForPeriod}")
    private int limitForPeriod;
    @Value("${patterns.config.rateLimiter.windowInMilliseconds}")
    private int windowInMilliseconds;
    @Value("${patterns.config.rateLimiter.waitTimeForThread}")
    private int waitTimeForThread;

    public int getInitialIntervalMillis() {
        return initialIntervalMillis;
    }

    public int getMultiplier() {
        return multiplier;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }


    public int getWaitTimeDuration() {
        return waitTimeDuration;
    }


    public int getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public int getPermittedNumberOfCallsInHalfOpenState() {
        return permittedNumberOfCallsInHalfOpenState;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public int getNumberOfThreads() {
        return numberOfThreads;
    }

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public int getWindowInMilliseconds() {
        return windowInMilliseconds;
    }

    public int getWaitTimeForThread() {
        return waitTimeForThread;
    }
}
