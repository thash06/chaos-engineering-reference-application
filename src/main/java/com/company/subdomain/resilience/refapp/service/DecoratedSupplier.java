package com.company.subdomain.resilience.refapp.service;

import com.company.subdomain.resilience.refapp.exception.ChaosEngineeringRuntimeException;
import com.company.subdomain.resilience.refapp.exception.TemporaryServiceOutageException;
import com.company.subdomain.resilience.refapp.model.MockDataServiceResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.vavr.CheckedFunction0;
import io.vavr.CheckedFunction1;
import io.vavr.CheckedFunction2;
import io.vavr.Function0;
import io.vavr.control.Try;
import lombok.Lombok;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class DecoratedSupplier {
    private static Logger LOGGER = LoggerFactory.getLogger(DecoratedSupplier.class);


    private final OfferingsDataService offeringsDataService;
    private final PatternsFactory patternsFactory;

    private AtomicInteger atomicInteger = new AtomicInteger(0);

    public DecoratedSupplier(OfferingsDataService offeringsDataService, PatternsFactory patternsFactory) {
        this.offeringsDataService = offeringsDataService;
        this.patternsFactory = patternsFactory;
    }

    public MockDataServiceResponse callRetryDecoratedService(boolean throwException) {
        handlePublishedEvents(patternsFactory.retry);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferingsWithRetry(throwException);
        return Retry.decorateSupplier(patternsFactory.retry, mockDataServiceResponseSupplier)
                .get();
    }

    public MockDataServiceResponse callRetryDecoratedServiceWithFallBack(boolean throwException) {
        handlePublishedEvents(patternsFactory.retry);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = () -> getMockOfferingsWithRetry(throwException);
        return Decorators.ofSupplier(mockDataServiceResponseSupplier)
                .withRetry(patternsFactory.retry)
                .withFallback(Arrays.asList(ConnectException.class, ResourceAccessException.class),
                        (e) -> fallbackResponse(String.format("Exception thrown: {%s}", e.getMessage())))
                .get();
    }

    private MockDataServiceResponse getMockOfferingsWithRetry(boolean throwException) {
        return Try.of(() -> offeringsDataService.getMockOfferingsForRetry(throwException))
                .recover(throwable -> {
                    if (throwable instanceof TemporaryServiceOutageException) {
                        throw new ChaosEngineeringRuntimeException(throwable.getMessage());
                    } else {
                        throw Lombok.sneakyThrow(throwable);
                    }
                })
                .get();
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

    public MockDataServiceResponse callSimpleCircuitBreakerDecoratedService(boolean throwException) throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.circuitBreaker);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = (() ->
                offeringsDataService.getMockOfferingsDataFromService(throwException));
        return CircuitBreaker.decorateSupplier(patternsFactory.circuitBreaker, mockDataServiceResponseSupplier)
                .get();
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

    public MockDataServiceResponse callSimpleBulkheadDecoratedService(boolean throwException) throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.bulkhead);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = (() ->
                offeringsDataService.getMockOfferingsDataFromService(throwException));
        return Bulkhead.decorateSupplier(patternsFactory.bulkhead, mockDataServiceResponseSupplier)
                .get();
    }

    /**
     * @param throwException
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws ChaosEngineeringRuntimeException
     */
    public MockDataServiceResponse callThreadPoolBulkheadAndTimeLimiterDecoratedService(boolean throwException)
            throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.threadPoolBulkhead);
        CompletableFuture<MockDataServiceResponse> future = Decorators
                .ofSupplier(() -> offeringsDataService.getDegradedMockOfferings(throwException))
                .withThreadPoolBulkhead(patternsFactory.threadPoolBulkhead)
                .withTimeLimiter(patternsFactory.timeLimiter, Executors.newSingleThreadScheduledExecutor())
                .withFallback(BulkheadFullException.class, (e) -> fallbackResponse(
                        String.format("Request failed due to bulkheadName {%s} BulkheadFullException", e.getMessage())))
                .withFallback(TimeoutException.class, (e) -> fallbackResponse(
                        String.format("Request failed due to TimeLimiter {%s} with duration {%s} due to TimeoutException",
                                patternsFactory.timeLimiter.getName(), patternsFactory.timeLimiter.getTimeLimiterConfig().getTimeoutDuration())))
                .get().toCompletableFuture();
        return future.get();
    }


    /**
     * @param throwException
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws ChaosEngineeringRuntimeException
     */
    public MockDataServiceResponse callBulkheadAndRetryDecoratedService(boolean throwException) throws ExecutionException, InterruptedException, ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.threadPoolBulkhead);
        //Retry retryContext = Retry.of("retry-for-bulkhead", RetryConfig.ofDefaults());
        //handlePublishedEvents(retryContext);
        Supplier<MockDataServiceResponse> serviceAsSupplier = createServiceAsSupplier(throwException);

        Supplier<CompletionStage<MockDataServiceResponse>> decorate = Decorators.ofSupplier(serviceAsSupplier)
                .withThreadPoolBulkhead(patternsFactory.threadPoolBulkhead)
                //.withRetry(retryContext, Executors.newSingleThreadScheduledExecutor())
                .decorate();

        CompletableFuture<MockDataServiceResponse> mockDataServiceResponseCompletionStage =
                decorate.get().toCompletableFuture();
        //return mockDataServiceResponseCompletionStage.getNow( getFallbackMockDataServiceResponse("Failed with Bulkhead and Retry"));
        return mockDataServiceResponseCompletionStage.get();
    }

    /**
     * @param throwException
     * @return
     * @throws ChaosEngineeringRuntimeException
     */
    public MockDataServiceResponse callDegradingOfferingsUsingSemaphoreBulkheadDecoratedService(boolean throwException)
            throws ChaosEngineeringRuntimeException, ExecutionException, InterruptedException {
        handlePublishedEvents(patternsFactory.circuitBreaker);
        handlePublishedEvents(patternsFactory.bulkhead);
        CheckedFunction0<MockDataServiceResponse> checkedFunction0 =
                CheckedFunction0.of(() -> offeringsDataService.getDegradedMockOfferings(throwException));
        Function0<MockDataServiceResponse> unchecked = checkedFunction0.unchecked();
//        Supplier<MockDataServiceResponse> timeLimiterDecoratedSupplier =
//                Bulkhead.decorateSupplier(decoratorFactory.bulkhead, unchecked);
        Supplier<CompletableFuture<MockDataServiceResponse>> futureSupplier = () -> CompletableFuture.supplyAsync(unchecked);
        Callable<MockDataServiceResponse> timeLimiterDecoratedSupplier =
                TimeLimiter.decorateFutureSupplier(patternsFactory.timeLimiter, futureSupplier);

        CompletableFuture<MockDataServiceResponse> completableFutureCompletionStage =
                Decorators.ofSupplier(() -> offeringsDataService.getDegradedMockOfferings(throwException))
                        .withThreadPoolBulkhead(patternsFactory.threadPoolBulkhead)
                        .withTimeLimiter(patternsFactory.timeLimiter, Executors.newSingleThreadScheduledExecutor())
                        .withCircuitBreaker(patternsFactory.circuitBreaker)
                        .withRetry(patternsFactory.retry, Executors.newSingleThreadScheduledExecutor())
                        .withFallback(BulkheadFullException.class, (e) -> {
                            LOGGER.info(" Recovering from BulkheadFullException {} ", e.getMessage());
                            return fallbackResponse(
                                    String.format("Request failed due to bulkheadName {%s} BulkheadFullException", e.getMessage()));
                        })
                        .withFallback(CallNotPermittedException.class, (e) -> {
                            LOGGER.info(" Recovering from CallNotPermittedException {} ", e.getMessage());
                            return fallbackResponse(
                                    String.format("Request failed due to circuitbreaker {%s} CallNotPermitted", e.getMessage()));
                        })
                        .withFallback(TimeoutException.class, (e) ->
                                {
                                    LOGGER.info(" Recovering from TimeoutException {} ", e.getMessage());
                                    return fallbackResponse(
                                            String.format("Request failed due to TimeLimiter {%s} with duration {%s} due to TimeoutException",
                                                    patternsFactory.timeLimiter.getName(), patternsFactory.timeLimiter.getTimeLimiterConfig().getTimeoutDuration()));
                                }
                        )
                        .get().toCompletableFuture();
        return completableFutureCompletionStage.get();
//        return Try.ofCallable(decoratedCallable)
//                .onFailure(throwable -> LOGGER.error(" Failure reason {} ", throwable.getMessage()))
//
//                .get();

//        Callable<MockDataServiceResponse> decoratedCallable = Decorators.ofCallable(
//                () -> chaosEngineeringDataService.getDegradedMockOfferings(throwException))
//                .withCircuitBreaker(decoratorFactory.circuitBreaker)
//                .decorate();
//        return Try.ofCallable(decoratedCallable)
//                .onFailure(throwable -> LOGGER.error(" Failure reason {} ", throwable.getMessage(), throwable))
//                .recoverWith(throwable -> Try.success(fallbackResponse(
//                        String.format("Request failed due to circuit-breaker {%s}", decoratorFactory.circuitBreaker.getName()))))
//                .get();
    }

    public MockDataServiceResponse callSemaphoreBulkheadDecoratedService(String offerId, boolean throwException) throws ChaosEngineeringRuntimeException {
        handlePublishedEvents(patternsFactory.bulkhead);
        if (throwException) {
            return checkedFunctionWithBulkheadDecorator(offerId, throwException);
        } else {
            return callableWithBulkheadDecorator(offerId, throwException);
        }
    }

    //////////////// Private Methods
    private MockDataServiceResponse callableWithBulkheadDecorator(String offerId, boolean throwException) {
        Callable<MockDataServiceResponse> callable = () ->
                offeringsDataService.getMockOfferingsDataFromService(offerId, throwException);
        Callable<MockDataServiceResponse> decoratedCallable = Decorators.ofCallable(callable)
                .withBulkhead(patternsFactory.bulkhead)
                .decorate();
        return Try.ofCallable(decoratedCallable)
                .onFailure(throwable -> LOGGER.error(" Failure reason {} ", throwable.getMessage(), throwable))
                .recoverWith(throwable -> Try.success(fallbackResponse(
                        String.format("Request with OfferId {%s} failed due to bulkhead {%s} full", offerId, patternsFactory.bulkhead.getName()))))
                .get();
    }

    private MockDataServiceResponse fallbackResponse(String message) {
        MockDataServiceResponse mockDataServiceResponse = new MockDataServiceResponse();
        mockDataServiceResponse.setHostedRegion(message);
        return mockDataServiceResponse;
    }

    private MockDataServiceResponse checkedFunctionWithBulkheadDecorator(String offerId, boolean throwException) throws ChaosEngineeringRuntimeException {
//        CheckedFunction1<String, MockDataServiceResponse> checkedFunction1 = createServiceAsCheckedFunction(throwException);
//        CheckedFunction1<String, MockDataServiceResponse> checkedFunction11 = Bulkhead.decorateCheckedFunction(decoratorFactory.bulkhead, checkedFunction1);
        CheckedFunction0<MockDataServiceResponse> checkedFunction0 = CheckedFunction0.of(() -> offeringsDataService.getMockOfferingsDataFromService(offerId, throwException));
        Function0<MockDataServiceResponse> unchecked = checkedFunction0.unchecked();
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = Bulkhead.decorateSupplier(patternsFactory.bulkhead, unchecked);
        return Try.ofSupplier(mockDataServiceResponseSupplier)
                .onFailure(throwable -> LOGGER.error(" Failure reason {} ", throwable.getMessage()))
                .recoverWith(throwable -> Try.success(
                        fallbackResponse(
                                String.format("Request with OfferId {%s} failed due to bulkhead {%s} full", offerId, patternsFactory.bulkhead.getName())))
                )
                .get();
    }


    private Supplier<MockDataServiceResponse> createServiceAsSupplier(boolean throwException) {
        handlePublishedEvents(patternsFactory.bulkhead);
        Supplier<MockDataServiceResponse> mockDataServiceResponseSupplier = (() -> {
            LOGGER.info("Invoking DecoratedController with Bulkhead count {} ", atomicInteger.incrementAndGet());
            return offeringsDataService.getMockOfferingsDataFromService(throwException);
        });
        return mockDataServiceResponseSupplier;
    }

    private CheckedFunction1<String, MockDataServiceResponse> createServiceAsCheckedFunction(boolean throwException) throws ChaosEngineeringRuntimeException {
        CheckedFunction1<String, MockDataServiceResponse> stringMockDataServiceResponseFunction = ((offerId) -> {
            LOGGER.info("Invoking DecoratedController with Bulkhead offerId {} count {} ", offerId, atomicInteger.incrementAndGet());
            return offeringsDataService.getMockOfferingsDataFromService(offerId, throwException);
        });
        return stringMockDataServiceResponseFunction;
    }

    private CheckedFunction2<String, Boolean, MockDataServiceResponse> createServiceAsCheckedFunction(String offerId, boolean throwException)
            throws ChaosEngineeringRuntimeException {
        CheckedFunction2<String, Boolean, MockDataServiceResponse> stringMockDataServiceResponseFunction = ((id, exception) -> {
            LOGGER.info("Invoking DecoratedController with Bulkhead offerId {} count {} ", id, atomicInteger.incrementAndGet());
            return offeringsDataService.getMockOfferingsDataFromService(id, exception);
        });
        return stringMockDataServiceResponseFunction;
    }


    //Monitoring by just logging
    private void handlePublishedEvents(Bulkhead bulkhead) {
        bulkhead.getEventPublisher()
                .onCallPermitted(event -> LOGGER.debug("Bulkhead Successful remote call {} ", Thread.currentThread().getName()))
                .onCallRejected(event -> LOGGER.info("Bulkhead Rejected remote call {} ", Thread.currentThread().getName()))
                .onCallFinished(event -> LOGGER.debug("Bulkhead Call Finished {} ", event));
    }

    private void handlePublishedEvents(ThreadPoolBulkhead threadPoolBulkhead) {
        threadPoolBulkhead.getEventPublisher()
                .onCallPermitted(event -> LOGGER.debug("ThreadPoolBulkhead Successful remote call {} ", Thread.currentThread().getName()))
                .onCallRejected(event -> LOGGER.info("ThreadPoolBulkhead Rejected remote call {} ", Thread.currentThread().getName()))
                .onCallFinished(event -> LOGGER.debug("ThreadPoolBulkhead Call Finished {} ", event));
    }

    private void handlePublishedEvents(Retry retry) {
        retry.getEventPublisher()
                .onError(event -> LOGGER.error(" Retry Event on Error {}", event))
                .onRetry(event -> LOGGER.info(" Retry Event on Retry {}", event))
                .onSuccess(event -> LOGGER.info(" Retry Event on Success {}", event))
                .onEvent(event -> LOGGER.debug(" Retry Event occurred records all events Retry, error and success {}", event));
    }

    private void handlePublishedEvents(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onCallNotPermitted(event -> LOGGER.info(" onCallNotPermitted {}", event))
                .onError(event -> LOGGER.debug(" onError {}", event))
                .onFailureRateExceeded(event -> LOGGER.debug(" onFailureRateExceeded {}", event))
                .onIgnoredError(event -> LOGGER.debug(" onIgnoredError {}", event))
                .onReset(event -> LOGGER.debug(" onReset {}", event))
                .onStateTransition(event -> LOGGER.debug(" onStateTransition something else {}", event.getStateTransition()))
                .onSuccess(event -> LOGGER.debug(" onSuccess {}", event));
    }

    private void handlePublishedEvents(RateLimiter rateLimiter) {
        rateLimiter.getEventPublisher()
                .onFailure(event -> LOGGER.error(" RateLimiter Event on Failure {}", event))
                .onSuccess(event -> LOGGER.debug(" RateLimiter Event on Success {}", event));
    }

    private void handlePublishedEvents(TimeLimiter timeLimiter) {
        timeLimiter.getEventPublisher()
                .onError(event -> LOGGER.error(" TimeLimiter Event on Failure {}", event))
                .onSuccess(event -> LOGGER.debug(" TimeLimiter Event on Success {}", event))
                .onTimeout(event -> LOGGER.info(" TimeLimiter Event on Timeout {}", event));
    }
}
