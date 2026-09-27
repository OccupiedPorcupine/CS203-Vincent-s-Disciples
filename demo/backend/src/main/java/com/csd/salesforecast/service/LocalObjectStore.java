package com.csd.salesforecast.service;

import java.nio.file.Path;

/** Keeps uploads on the local volume only, as the demo did before object storage. */
public class LocalObjectStore implements ObjectStore {
    @Override public String bucket() { return null; }
    @Override public void put(String key, Path file, String contentType) {}
    @Override public void download(String key, Path target) {
        throw new IllegalStateException("Object storage is not configured; cannot fetch " + key);
    }
}
