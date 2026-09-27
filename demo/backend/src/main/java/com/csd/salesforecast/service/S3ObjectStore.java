package com.csd.salesforecast.service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.*;
import java.nio.file.*;

/** S3-compatible object storage, used with Supabase Storage's S3 endpoint. */
public class S3ObjectStore implements ObjectStore {
    private final S3Client client;
    private final String bucket;

    public S3ObjectStore(S3Client client, String bucket) { this.client = client; this.bucket = bucket; }

    @Override public String bucket() { return bucket; }

    @Override
    public void put(String key, Path file, String contentType) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), RequestBody.fromFile(file));
    }

    @Override
    public void download(String key, Path target) throws IOException {
        Path partial = Files.createTempFile(target.getParent(), "download-", ".part");
        try (InputStream input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }
}
