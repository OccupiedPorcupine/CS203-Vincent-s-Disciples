package com.csd.salesforecast.service;

import java.io.IOException;
import java.nio.file.Path;

/** Durable storage for raw source files. The ML service still reads local working copies. */
public interface ObjectStore {
    /** Bucket recorded against published files, or null when files only live on local disk. */
    String bucket();
    void put(String key, Path file, String contentType) throws IOException;
    void download(String key, Path target) throws IOException;
}
