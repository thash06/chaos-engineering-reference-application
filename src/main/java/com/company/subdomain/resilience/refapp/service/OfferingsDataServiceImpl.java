package com.company.subdomain.resilience.refapp.service;

import com.company.subdomain.resilience.refapp.exception.ChaosEngineeringRuntimeException;
import com.company.subdomain.resilience.refapp.exception.TemporaryServiceOutageException;
import com.company.subdomain.resilience.refapp.model.MockDataServiceResponse;
import com.company.subdomain.resilience.refapp.model.Offering;
import com.company.subdomain.resilience.refapp.repository.ChaosEngineeringDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;



@Service
@Component(value = "offeringsDataService")
public class OfferingsDataServiceImpl implements OfferingsDataService {
    private static final int[] FIBONACCI = new int[]{1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233};
    private static Logger LOGGER = LoggerFactory.getLogger(OfferingsDataServiceImpl.class);
    private final ChaosEngineeringDataRepository chaosEngineeringDataRepository;
    private AtomicInteger atomicInteger = new AtomicInteger(0);

    public OfferingsDataServiceImpl(ChaosEngineeringDataRepository chaosEngineeringDataRepository) {
        this.chaosEngineeringDataRepository = chaosEngineeringDataRepository;
    }

    @Override
    public MockDataServiceResponse getMockOfferings(Boolean throwException) throws TemporaryServiceOutageException {
        LOGGER.info("Invoking OfferingsDataServiceImpl throwException {} count {}", throwException, atomicInteger.incrementAndGet());
        if (throwException && atomicInteger.get() < 2) {
            throw new TemporaryServiceOutageException("TemporaryServiceOutageException thrown from service count " + atomicInteger.get());
        }
        String hostedRegion = "";
        List<Offering> mockOffers = chaosEngineeringDataRepository.getSampleDataFromRepository();
        MockDataServiceResponse response = new MockDataServiceResponse();
        response.setData(mockOffers);
        response.setHostedRegion(hostedRegion);
        return response;
    }

    /**
     * This function returns sample data from the repository layer along
     * with the hosted AWS region!
     */
    @Override
    public MockDataServiceResponse getMockOfferingsDataFromService(boolean throwException) throws ChaosEngineeringRuntimeException {
        LOGGER.info("Invoking OfferingsDataServiceImpl throwException {} count {}", throwException, atomicInteger.incrementAndGet());
        if (throwException) {
            throw new ChaosEngineeringRuntimeException("Something went wrong!!");
        }
        String hostedRegion = "";
        List<Offering> mockOffers = chaosEngineeringDataRepository.getSampleDataFromRepository();
        MockDataServiceResponse response = new MockDataServiceResponse();
        response.setData(mockOffers);
        response.setHostedRegion(hostedRegion);
        return response;
    }

    @Override
    public MockDataServiceResponse getMockOfferingsDataFromService(String id, boolean throwException) throws ChaosEngineeringRuntimeException {
        LOGGER.info("Invoking OfferingsDataServiceImpl throwException {} count {}", throwException, atomicInteger.incrementAndGet());
        if (throwException) {
            throw new ChaosEngineeringRuntimeException("Something went wrong!!");
        }
        String hostedRegion = "";

        List<Offering> mockOffers = chaosEngineeringDataRepository.getSampleDataFromRepositoryById(id);
        MockDataServiceResponse response = new MockDataServiceResponse();
        response.setData(mockOffers);
        response.setHostedRegion(hostedRegion);
        return response;
    }


    @Override
    public MockDataServiceResponse getDegradedMockOfferings(boolean throwException) throws ChaosEngineeringRuntimeException {
        int requestNumber = atomicInteger.incrementAndGet();
        int index = atomicInteger.getAndIncrement();
        int sleepDuration = FIBONACCI[index % FIBONACCI.length] * 100;
        LOGGER.info("Starting degrading service count {} request degrades by {} ", requestNumber, sleepDuration);
        if (throwException) {
            throw new ChaosEngineeringRuntimeException("No need to degrade just fail!!");
        }
        String hostedRegion = "";
        //Sleep to emulate a degrading service
        try {

            Thread.sleep(sleepDuration);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        List<Offering> mockOffers = chaosEngineeringDataRepository.getSampleDataFromRepository();
        MockDataServiceResponse response = new MockDataServiceResponse();
        List<Offering> slimOfferings = mockOffers.stream()
                .map(offering -> {
                    Offering newInstance = new Offering();
                    newInstance.setOfferId(offering.getOfferId());
                    return newInstance;
                }).collect(Collectors.toList());
        response.setData(slimOfferings);
        response.setHostedRegion(hostedRegion);
        LOGGER.info("Sending Response for request {} : {} ", requestNumber, response);
        return response;
    }
}
