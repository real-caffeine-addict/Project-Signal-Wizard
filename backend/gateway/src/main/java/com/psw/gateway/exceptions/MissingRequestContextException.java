package com.psw.gateway.exceptions;

public class MissingRequestContextException extends RuntimeException {
    public MissingRequestContextException(String message) {
        super(message);
    }
}
