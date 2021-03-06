package com.company.subdomain.resilience.refapp.controller;

import com.company.subdomain.resilience.refapp.ChaosEngineeringReferenceApplication;
import com.company.subdomain.resilience.refapp.model.MockDataServiceResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(classes = ChaosEngineeringReferenceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DecoratedControllerTest {
    @LocalServerPort
    private int port;

    @Test
    @Order(1)
    void testSemaphoreBulkhead() throws ExecutionException, InterruptedException {
        String url = String.format("http://localhost:%d/decorated-services/simpleSemaphoreBulkhead", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, 1010, throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, 1011, throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, 1012, throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, 1013, throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, 1014, throwException);
        Mono<MockDataServiceResponse> six = submitRESTRequest(webClient, 1015, throwException);
        Mono<MockDataServiceResponse> seven = submitRESTRequest(webClient, 1016, throwException);
        Mono<MockDataServiceResponse> eight = submitRESTRequest(webClient, 1017, throwException);
        Mono<MockDataServiceResponse> nine = submitRESTRequest(webClient, 1018, throwException);
        Mono<MockDataServiceResponse> ten = submitRESTRequest(webClient, 1019, throwException);
        Mono<List<MockDataServiceResponse>> listMono = Flux.merge(one, two, three, four, five, six, seven, eight, nine, ten)
                .collectList();
        CompletableFuture<List<MockDataServiceResponse>> listCompletableFuture = listMono.toFuture();
        List<MockDataServiceResponse> successfulRequests = listCompletableFuture.get().stream()
                .filter(val -> val.getData() != null)
                .collect(Collectors.toList());
        List<String> failedRequests = listCompletableFuture.get().stream()
                .filter(val -> val.getData() == null || val.getData().isEmpty())
                .map(val -> val.getHostedRegion())
                .collect(Collectors.toList());
        assertEquals(4, successfulRequests.size());
        assertEquals(6, failedRequests.size());
    }


    @Test
    @Order(2)
    void testSimpleRetry() {
        String url = String.format("http://localhost:%d/decorated-services/simpleRetry", port);
        WebClient webClient = WebClient.create(url);
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, true);

        try {
            MockDataServiceResponse mockDataServiceResponse = one.toFuture().get();
            assertEquals(mockDataServiceResponse.getData().size(), 100);
        } catch (Exception e) {
            fail();
        }
    }

    /**
     * 5 concurrent calls to a service which is protected by a Bulkhead which is able to handle 4 concurrent requests.
     * The result was 4 calls succeeded and 1 failed. This is because we have set the maxWaitDuration(Duration.ofMillis(0))
     * to zero which does not allow the request to retry after the first attempt even when a Thread becomes available for processing.
     *
     * @throws Exception
     */
    @Test
    @Order(3)
    void testSimpleBulkhead_Semaphore() throws Exception {
        String url = String.format("http://localhost:%d/decorated-services/simpleBulkhead", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, 1010, throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, 1011, throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, 1012, throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, 1013, throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, 1014, throwException);
        Mono<List<MockDataServiceResponse>> listMono = Flux.merge(one, two, three, four, five)
                .map(result -> result)
                .collectList();
        CompletableFuture<List<MockDataServiceResponse>> listCompletableFuture = listMono.toFuture();
        try {
            boolean resultFound = false;
            while (!resultFound) {
                if (listCompletableFuture.isDone()) {
                    List<MockDataServiceResponse> successfulRequests = listCompletableFuture.get().stream()
                            .filter(val -> val.getData() != null)
                            .collect(Collectors.toList());
                    List<String> failedRequests = listCompletableFuture.get().stream()
                            .filter(val -> val.getData() == null || val.getData().isEmpty())
                            .map(val -> val.getHostedRegion())
                            .collect(Collectors.toList());
                    resultFound = true;
                    assertEquals(4, successfulRequests.size());
                    assertEquals(1, failedRequests.size());
                    assertEquals("BulkheadFullException thrown: {Bulkhead 'semaphore-bulkhead' is full and does not permit further calls}", failedRequests.get(0));
                }
                Thread.sleep(1000);
            }


        } catch (Throwable e) {
            fail();
        }
    }

    /**
     * 10 sequential calls to a service which is protected by a CircuitBreaker which is configured with a
     * failure threshold of 25% out of a sliding window of 4 requests (i.e 1 out of 4 failures will trigger a
     * CircuitBreaker state change).
     * The first 4 requests are set to throw exceptions and fail (!throwException) .
     * This causes the CircuitBreaker state to OPEN after the first 4 requests. The response from the service method is "Something went wrong!!"
     * The next 6 requests are configured to return successfully but since the CircuitBreaker is in OPEN state calls are not forwarded
     * to the service method and a CallNotPermittedException is thrown which is handled in the fallback().
     * The CircuitBreaker is also configured to check the state of the endpoint after 25 milliseconds waitDurationInOpenState(Duration.ofMillis(25)).
     * This is the HALF_OPEN state and if it gets a successful response it switches state to CLOSED or else to OPEN.
     * In the 25 milliseconds that it is in OPEN state it was able to send 4 requests and each threw  CallNotPermittedException.
     * After 25 milliseconds when it moved to HALF_OPEN and sent one request to the remote service and got a response it switched back
     * to closed state and got a sucessful response for the last 2 requests.
     *
     * @throws Exception
     */
    @Test
    @Order(4)
    void testSimpleCircuitBreaker() throws Exception {
        String url = String.format("http://localhost:%d/decorated-services/simpleCircuitBreaker", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, 1010, !throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, 1011, !throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, 1012, !throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, 1013, !throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, 1014, !throwException);
        Mono<MockDataServiceResponse> six = submitRESTRequest(webClient, 1015, !throwException);
        Mono<MockDataServiceResponse> seven = submitRESTRequest(webClient, 1016, throwException);
        Mono<MockDataServiceResponse> eight = submitRESTRequest(webClient, 1017, throwException);
        Mono<MockDataServiceResponse> nine = submitRESTRequest(webClient, 1018, throwException);
        Mono<MockDataServiceResponse> ten = submitRESTRequest(webClient, 1019, throwException);
        try {

            List<MockDataServiceResponse> successfulMonos = new ArrayList<>();
            List<Mono<MockDataServiceResponse>> monos = Arrays.asList(one, two, three, four, five, six, seven, eight, nine, ten);
            for (Mono<MockDataServiceResponse> mono : monos) {
                MockDataServiceResponse mockDataServiceResponse = mono.toFuture().get();
                successfulMonos.add(mockDataServiceResponse);
            }
            List<MockDataServiceResponse> circuitBreakerFailures = successfulMonos.stream()
                    .filter(response -> response.getHostedRegion()
                            .equals("CircuitBreaker 'circuit-breaker' is OPEN and does not permit further calls"))
                    .collect(Collectors.toList());
            List<MockDataServiceResponse> regularFailures = successfulMonos.stream()
                    .filter(response -> response.getHostedRegion()
                            .contains("TemporaryServiceOutageException thrown from service"))
                    .collect(Collectors.toList());
            List<MockDataServiceResponse> successfulResponses = successfulMonos.stream()
                    .filter(response -> response.getData() != null)
                    .collect(Collectors.toList());
            assertEquals(regularFailures.size(), 4);
            assertEquals(circuitBreakerFailures.size(), 4);
            assertEquals(successfulResponses.size(), 2);

        } catch (Exception e) {
            fail();
        }
    }

    /**
     * RateLimiter has been configured to allow 4 requests every 10 seconds. When we send 8 sequential requests on my machine
     * 4 of them are successful and 4 fail due to RequestNotPermitted.
     *
     * @throws Exception
     */

    @Test
    @Order(5)
    void testRateLimiter() throws Exception {
        String url = String.format("http://localhost:%d/decorated-services/simpleRateLimiter", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, 1010, throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, 1011, throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, 1012, throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, 1013, throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, 1014, throwException);
        Mono<MockDataServiceResponse> six = submitRESTRequest(webClient, 1015, throwException);
        Mono<MockDataServiceResponse> seven = submitRESTRequest(webClient, 1016, throwException);
        Mono<MockDataServiceResponse> eight = submitRESTRequest(webClient, 1017, throwException);
        try {
            List<MockDataServiceResponse> successfulMonos = new ArrayList<>();
            List<Mono<MockDataServiceResponse>> monos = Arrays.asList(one, two, three, four, five, six, seven, eight);
            for (Mono<MockDataServiceResponse> mono : monos) {
                MockDataServiceResponse mockDataServiceResponse = mono.toFuture().get();
                successfulMonos.add(mockDataServiceResponse);
            }
            List<MockDataServiceResponse> rateLimiterFailures = successfulMonos.stream()
                    .filter(response -> response.getHostedRegion()
                            .equals("RequestNotPermitted thrown: {RateLimiter 'rate-limiter' does not permit further calls}"))
                    .collect(Collectors.toList());
            List<MockDataServiceResponse> successfulResponses = successfulMonos.stream()
                    .filter(response -> response.getData() != null)
                    .collect(Collectors.toList());
            assertEquals(rateLimiterFailures.size(), 4);
            assertEquals(successfulResponses.size(), 4);

        } catch (Exception e) {
            fail();
        }
    }

    /**
     * @throws Exception
     */
    @Test
    @Order(6)
    void testTimeLimiter() throws Exception {
        String url = String.format("http://localhost:%d/decorated-services/simpleTimeLimiter", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, 1010, throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, 1011, throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, 1012, throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, 1013, throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, 1014, throwException);
        Mono<MockDataServiceResponse> six = submitRESTRequest(webClient, 1015, throwException);
        Mono<MockDataServiceResponse> seven = submitRESTRequest(webClient, 1016, throwException);
        Mono<MockDataServiceResponse> eight = submitRESTRequest(webClient, 1017, throwException);
        try {
            List<MockDataServiceResponse> successfulMonos = new ArrayList<>();
            List<Mono<MockDataServiceResponse>> monos = Arrays.asList(one, two, three, four, five, six, seven, eight);
            for (Mono<MockDataServiceResponse> mono : monos) {
                MockDataServiceResponse mockDataServiceResponse = mono.toFuture().get();
                successfulMonos.add(mockDataServiceResponse);
            }
            List<MockDataServiceResponse> timeLimiterFailures = successfulMonos.stream()
                    .filter(response -> response.getHostedRegion()
                            .equals("TimeLimiter does not set an exception message so creating one"))
                    .collect(Collectors.toList());
            List<MockDataServiceResponse> successfulResponses = successfulMonos.stream()
                    .filter(response -> response.getData() != null)
                    .collect(Collectors.toList());
            assertEquals(timeLimiterFailures.size(), 3);
            assertEquals(successfulResponses.size(), 5);

        } catch (Exception e) {
            fail();
        }
    }

    /**
     * This test calls a degrading service so it may fail on other machines. It works on  mine.
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    @Test
    @Order(7)
    void testThreadPoolBulkheadWithTimeLimiter() throws ExecutionException, InterruptedException {
        String url = String.format("http://localhost:%d/decorated-services/offeringsWithThreadPoolBulkheadAndTimeLimiter", port);
        WebClient webClient = WebClient.create(url);
        Boolean throwException = Boolean.FALSE;
        Mono<MockDataServiceResponse> one = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> two = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> three = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> four = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> five = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> six = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> seven = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> eight = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> nine = submitRESTRequest(webClient, throwException);
        Mono<MockDataServiceResponse> ten = submitRESTRequest(webClient, throwException);
        Mono<List<MockDataServiceResponse>> listMono = Flux.merge(one, two, three, four, five, six, seven, eight, nine, ten)
                .map(result -> result)
                .collectList();
        CompletableFuture<List<MockDataServiceResponse>> listCompletableFuture = listMono.toFuture();
        List<MockDataServiceResponse> successfulRequests = listCompletableFuture.get().stream()
                .filter(val -> val.getData() != null)
                .collect(Collectors.toList());
        List<String> failedRequests = listCompletableFuture.get().stream()
                .filter(val -> val.getData() == null || val.getData().isEmpty())
                .map(val -> val.getHostedRegion())
                .collect(Collectors.toList());
        assertEquals(3, successfulRequests.size());
        assertEquals(7, failedRequests.size());
        assertEquals(2,
                failedRequests.stream().filter(response -> response.contains("thread-pool-bulkhead")).collect(Collectors.toList()).size());
        assertEquals(5,
                failedRequests.stream().filter(response -> response.contains("time-limiter")).collect(Collectors.toList()).size());
    }
    /////////       Private methods

    private Mono<MockDataServiceResponse> submitRESTRequest(WebClient webClient, int offerId, Boolean throwException) {
        return webClient.get().uri("?offerId={offerId}&throwException={throwException}", offerId, throwException)
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, clientResponse ->
                        Mono.error(new Throwable(clientResponse.statusCode().getReasonPhrase()))
                )
                .onStatus(HttpStatus::is5xxServerError, clientResponse ->
                        Mono.error(new Throwable(clientResponse.statusCode().getReasonPhrase()))
                )
                .bodyToMono(MockDataServiceResponse.class);
    }

    private Mono<MockDataServiceResponse> submitRESTRequest(WebClient webClient, Boolean throwException) {
        return webClient.get().uri("?throwException={throwException}", throwException)
                .retrieve()
                .onStatus(HttpStatus::is4xxClientError, clientResponse ->
                        Mono.error(new Throwable(clientResponse.statusCode().getReasonPhrase()))
                )
                .onStatus(HttpStatus::is5xxServerError, clientResponse ->
                        Mono.error(new Throwable(clientResponse.statusCode().getReasonPhrase()))
                )
                .bodyToMono(MockDataServiceResponse.class);
    }
}
