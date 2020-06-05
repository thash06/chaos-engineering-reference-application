# Resilience patterns
This project consists of implementations of a few patterns that allow services achieve fault tolerance 
(i.e resilience) in the face of events such as service failure, too many concurrent requests etc. 
Failures cannot be prevented, but the goal should be to build solutions that fail fast and gracefully rather than 
cascading and bringing down the whole system.

Netflix Hystrix a latency and fault tolerance library was one of the pioneer's of resilience engineering but as Netflix 
has moved towards more adaptive solutions this library has been in maintenance mode since 2018 and Resilience4j has been 
filing the void.

Resilience4j is a framework that provides higher-order functions (decorators) and/or annotations to enhance any method call, 
functional interface, lambda expression or method reference with a Circuit Breaker, Rate Limiter, Retry or Bulkhead. 
We can choose to use one or more of these "Decorators" to meet our resilience objective.

## Resilience Patterns using Resilience4j
To demonstrate resilience4j patterns we have a service `OfferingsDataServiceImpl` which is granted access only through a 
class (`DecoratedSupplier`) which acts as a proxy protecting the endpoint using a layer of resilience4j decorators.  
 
- **chaos-engineering-reference-application** - An application where the controller (`DecoratedController`) accesses the service containing 
the business logic through a layer called `DecoratedSupplier` which decorates the final business service endpoint
with resilience pattern implementations.
The `DecoratedSupplier` has examples of chaining different patterns together as well as how to use `fallback`.


### Retry with exponential backoff
In the event of failure due to unavailability or any of the Exceptions listed in `retryExceptions()` method listed below, 
applications can choose to return a fallback/default return value or choose to keep the connection open and retry the endpoint which threw the error.
The retry logic can use simple bounded/timed retries or advanced retrying methods such as  exponential backoff. 

The code snippet below creates a retry config which allows a maximum of `maxAttempts` retries where the first retry will be after 
`initialIntervalMillis` milliseconds and each subsequent retry will be a multiple (`multiplier` in this case) of the previous. 

    public MockDataServiceResponse callRetryDecoratedService(boolean throwException) {
        handlePublishedEvents(patternsFactory.retry);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferings(throwException);
        return Retry.decorateSupplier(patternsFactory.retry, mockDataServiceResponseSupplier).get();
    }
    
    private MockDataServiceResponse getMockOfferings(boolean throwException) {
        return Try.of(() -> offeringsDataService.getMockOfferings(throwException))
                .recover(throwable -> {
                    if (throwable instanceof TemporaryServiceOutageException) {
                        throw new ChaosEngineeringRuntimeException(throwable.getMessage());
                    } else {
                        throw Lombok.sneakyThrow(throwable);
                    }
                })
                .get();
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
    
 The example above could easily be tuned to return a default cached/default response rather than retry with a single 
 line code change.
 
    public MockDataServiceResponse callRetryDecoratedServiceWithFallBack(boolean throwException) {
        handlePublishedEvents(patternsFactory.retry);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferings(throwException);
        return Decorators.ofSupplier(mockDataServiceResponseSupplier)
                .withRetry(patternsFactory.retry)
                .withFallback(Arrays.asList(ConnectException.class, ResourceAccessException.class), 
                        (e) -> fallbackResponse(String.format("Exception thrown: {%s}", e.getMessage())))
                .get();
    }

### CircuitBreaker
In cases where default value is not an option and the remote system does not "heal" or respond even after repeated retries 
we can prevent further calls to the downstream system. The Circuit Breaker is one such method which helps us in preventing a 
cascade of failures when a remote service is down.
CircuitBreaker has 3 states- 
- **OPEN** -  Rejects calls to remote service with a CallNotPermittedException when it is OPEN.
- **HALF_OPEN** - Permits a configurable number of calls to see if the backend is still unavailable or has become available again.
- **CLOSED** - Calls can be made to the remote system. This happens when the failure rate is below the threshold.

Two other states are also supported
- **DISABLED** - always allow access.
- **FORCED_OPEN** - always deny access

The transition happens from `CLOSED` to `OPEN` state based upon 
- **Count based sliding window** -  How many of the last N calls have failed. 
- **Time based sliding window** - How many failures occurred in the last N minutes (or any other duration).


A few settings can be configured for a Circuit Breaker:

1. The failure rate threshold above which the CircuitBreaker opens and starts short-circuiting calls.
2. The wait duration which defines how long the CircuitBreaker should stay open before it switches to HALF_OPEN.
3. A custom CircuitBreakerEventListener which handles CircuitBreaker events.
4. A Predicate which evaluates if an exception should count as a failure.


The code snippet below configures the CircuitBreaker and registers it against a global name and then attaches it to the 
method supplier. 
Two important settings are discussed below and the rest are self-explanatory.
- **The `failureRateThreshold`** - value specifies what percentage of remote calls should fail for the state to change from CLOSED to OPEN. 
- **The `slidingWindowSize()`** - property specifies the number of calls which will be used to determine the failure threshold percentage.

Eg: If in the last 5 remote calls 20% or 1 call failed due to  `ChaosEngineeringRuntimeException.class, TemporaryServiceOutageException.class` then the 
CircuitBreaker status changes to OPEN.
It stays in Open state for waitDurationInOpenState() milliseconds then allows the number  specified in
`permittedNumberOfCallsInHalfOpenState()` to go through to determine if the status can go back to CLOSED or stay in OPEN.


    public MockDataServiceResponse callCircuitBreakerDecoratedService(boolean throwException) {
        handlePublishedEvents(patternsFactory.circuitBreaker);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferings(throwException);
        Supplier<MockDataServiceResponse> decoratedSupplier = Decorators.ofSupplier(mockDataServiceResponseSupplier)
                .withCircuitBreaker(patternsFactory.circuitBreaker)
                .decorate();
        return Try.ofSupplier(decoratedSupplier)
                .onFailure(throwable -> LOGGER.error("Request failed due to {}", throwable.getMessage()))
                .getOrElseGet(throwable -> fallbackResponse(String.format(throwable.getMessage())));
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
    
### Time Limiter
The code snippet below creates a TimeLimiter with a configurable `timeoutDuration()` which states that if the remote call execution 
takes longer than the `timeoutDuration()` the call is terminated and an exception or a cached/fallback value returned to caller.
One important caveat about the `cancelRunningFuture()` is that the value `true` only works when TimeLimiter is used to decorate 
a method which returns a Future. 
For a better understanding of how this property works differently in `Future` and `CompletableFuture` refer to 
the unit test `com.company.subdomain.resilience.refapp.util.TimeLimiterTest`. Run this test by switching the `cancelRunningFuture()`
to `false` and see how the output changes.
To gain a better understanding of why `CompletableFuture` cannot be interrupted refer to this fantastic tutorial.
https://www.nurkiewicz.com/2015/03/completablefuture-cant-be-interrupted.html?m=1

```
    private TimeLimiter createTimeLimiter(int waitTimeForThread) {
        TimeLimiterConfig timeLimiterConfig = TimeLimiterConfig.custom()
                .cancelRunningFuture(true)
                .timeoutDuration(Duration.ofMillis(waitTimeForThread))
                .build();
        TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.of(timeLimiterConfig);
        return timeLimiterRegistry.timeLimiter(TIME_LIMITER);
    }
    public MockDataServiceResponse callTimeLimiterDecoratedService(boolean throwException) throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.timeLimiter);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = (() ->
                offeringsDataService.getDegradedMockOfferings(throwException));
        Supplier<CompletableFuture<MockDataServiceResponse>> futureSupplier =
                () -> CompletableFuture.supplyAsync(mockDataServiceResponseSupplier);

        Callable<MockDataServiceResponse> decorateFutureSupplier =
                TimeLimiter.decorateFutureSupplier(patternsFactory.timeLimiter, futureSupplier);


        //https://github.com/resilience4j/resilience4j/issues/928
        //TimeLimiter does not set an exception message so creating one see issue number above
        return Try.of(decorateFutureSupplier::call)
                .onFailure(throwable -> LOGGER.error("Request failed due to {}", throwable.getMessage()))
                .getOrElseGet(throwable -> {
                    LOGGER.error("Request failed due to {}", throwable.getMessage());
                    return fallbackResponse("TimeLimiter does not set an exception message so creating one");
                });
    }
```


### Rate Limiter
Rate limiting is an imperative technique to prepare your API for scale and establish high availability and reliability of 
your service.
The code snippet below creates a RateLimiter instance which allows only a specified number of calls (`limitForPeriod(limitForPeriod)`)
in a time window `limitRefreshPeriod(Duration.ofSeconds(windowInSeconds))`. Calls that exceed the limit can wait for
the duration specified in `timeoutDuration(Duration.ofMillis(waitTimeForThread))`. 
Requests that do  not get processed within the `limitRefreshPeriod + timeoutDuration` are rejected.

```
    private RateLimiter createRateLimiter(int limitForPeriod, int windowInMilliseconds, int waitTimeForThread) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMillis(windowInMilliseconds))
                .limitForPeriod(limitForPeriod)
                .timeoutDuration(Duration.ofMillis(waitTimeForThread))
                .build();
        RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(rateLimiterConfig);
        return rateLimiterRegistry.rateLimiter(RATE_LIMITER);
    }
    public MockDataServiceResponse callRateLimiterDecoratedService(boolean throwException) throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.rateLimiter);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = (() ->
                offeringsDataService.getMockOfferingsDataFromService(throwException));
        Supplier<MockDataServiceResponse> decoratedSupplier = Decorators.ofSupplier(mockDataServiceResponseSupplier)
                .withRateLimiter(patternsFactory.rateLimiter)
                .withFallback(Collections.singletonList(RequestNotPermitted.class),
                        (e) -> fallbackResponse(String.format("RequestNotPermitted thrown: {%s}", e.getMessage())))
                .decorate();
        return Try.ofSupplier(decoratedSupplier)
                .onFailure(throwable -> LOGGER.error("Request failed due to {}", throwable.getMessage()))
                .getOrElseGet(throwable -> fallbackResponse(String.format(throwable.getMessage())));
    }
```

### Bulkhead
Used to limit the number of concurrent calls to a service. If clients send more than the number of concurrent calls 
(**referred to as the saturation point and configured using the maxConcurrentCalls()**) than the service is configured to handle, 
a Bulkhead decorated service protects it from getting overwhelmed by keeping the additional calls waiting for a preconfigured time 
(**configured through maxWaitTime()**). 
If during this wait time any of the threads handing the existing concurrent calls becomes available the waiting calls get their turn 
to execute else these calls are rejected by the Bulkhead decorator by throwing a BulkheadFullException stating that  
"Bulkhead _<bulkhead-name>_ is full and does not permit further calls".

Applying a Bulkhead decorator to a service can be done in 2 easy steps.
1.  Create a Bulkhead using custom or default configuration. In the example below the Bulkhead will allow the
    service that it protected to accept _maxConcurrentCalls_ calls concurrently. While the request is being processed any new incoming request will be 
    queued for _maxWaitDuration_ and if during this time any of the concurrent calls complete and frees up the Thread the waiting request will get 
    forwarded to the service otherwise a BulkheadFullException is thrown.


```
    private Bulkhead createBulkhead(int availableProcessors) {
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(availableProcessors)
                .maxWaitDuration(Duration.ofMillis(0))
                .writableStackTraceEnabled(true)
                .build();

        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);
        return bulkheadRegistry.bulkhead(SEMAPHORE_BULKHEAD);
    }
```

2.  Decorate the service using the Bulkhead created above.

```
    public MockDataServiceResponse callBulkheadDecoratedService(boolean throwException) throws Throwable {
        LOGGER.info(" {} callBulkheadDecoratedService ", Thread.currentThread().getName());
        handlePublishedEvents(patternsFactory.bulkhead);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferings(throwException);
        Supplier<MockDataServiceResponse> decoratedSupplier = Decorators.ofSupplier(mockDataServiceResponseSupplier)
                .withBulkhead(patternsFactory.bulkhead)
                .withFallback(Collections.singletonList(BulkheadFullException.class),
                        (e) -> fallbackResponse(String.format("BulkheadFullException thrown: {%s}", e.getMessage())))
                .decorate();
        return Try.ofSupplier(decoratedSupplier)
                .getOrElseGet(throwable -> fallbackResponse(throwable.getMessage()));
    }
```



**Note**: There are 2 Bulkhead implementations provided by Resilience4j and the decision of which one to choose can get a bit tricky.
Here are a few points which may help with that decision.
1. In SemaphoreBulkhead the number of concurrent threads to run is controlled by a Semaphore but the user code runs in the current Thread. 
   Also this bulkhead returns the actual response object without wrapping it in a Future.
2. In ThreadPoolBulkhead the number of concurrent threads to run is controlled by a ThreadPool size and the user code runs in a Thread from the ThreadPool. 
   Also this bulkhead returns the response object wrapped in a Future. 
