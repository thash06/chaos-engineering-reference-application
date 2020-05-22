package com.company.subdomain.resilience.refapp.service;

import com.company.subdomain.resilience.refapp.exception.ChaosEngineeringRuntimeException;
import com.company.subdomain.resilience.refapp.exception.TemporaryServiceOutageException;
import com.company.subdomain.resilience.refapp.model.MockDataServiceResponse;

public interface OfferingsDataService {
    MockDataServiceResponse getMockOfferings(Boolean throwException) throws TemporaryServiceOutageException;

    MockDataServiceResponse getMockOfferingsDataFromService(boolean throwException) throws ChaosEngineeringRuntimeException;

    MockDataServiceResponse getMockOfferingsDataFromService(String id, boolean throwException) throws ChaosEngineeringRuntimeException;

    MockDataServiceResponse getDegradedMockOfferings(boolean throwException) throws ChaosEngineeringRuntimeException;
}
