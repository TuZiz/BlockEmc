package com.blockemc.service.storage;

public final class AccountStorageException extends Exception {

    public AccountStorageException(String message) {
        super(message);
    }

    public AccountStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
