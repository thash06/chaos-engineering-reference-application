package com.company.subdomain.resilience.refapp.exception;

public class TemporaryServiceOutageException extends Exception {

    public TemporaryServiceOutageException(String cause) {
        super(cause);
    }
}
