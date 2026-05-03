package com.altrix.common.exception;

import lombok.Getter;

@Getter
public abstract class BasePlatformException extends RuntimeException {
    private final String errorCode;

    protected BasePlatformException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}
