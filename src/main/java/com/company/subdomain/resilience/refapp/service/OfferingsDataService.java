package com.company.subdomain.resilience.refapp.service;

import com.company.subdomain.resilience.refapp.exception.ChaosEngineeringException;
import com.company.subdomain.resilience.refapp.model.MockDataServiceResponse;

public interface OfferingsDataService {
    MockDataServiceResponse getMockOfferingsDataFromService(boolean throwException) throws ChaosEngineeringException;

    MockDataServiceResponse getMockOfferingsDataFromService(String id, boolean throwException) throws ChaosEngineeringException;

    MockDataServiceResponse getDegradedMockOfferings(boolean throwException) throws ChaosEngineeringException;
}
