package com.blockemc.service.storage;

public interface AccountStorage extends AutoCloseable {

    AccountSnapshot load() throws AccountStorageException;

    void save(AccountSnapshot snapshot) throws AccountStorageException;

    @Override
    default void close() throws AccountStorageException {
    }
}
