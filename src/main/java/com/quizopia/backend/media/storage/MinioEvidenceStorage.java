package com.quizopia.backend.media.storage;

import com.quizopia.backend.media.storage.EvidenceStorage.ObjectHead;
import com.quizopia.backend.media.storage.EvidenceStorage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link EvidenceStorage} over MinIO (S3 API). Created when an access key is
 * configured; the bucket is ensured once at startup. All calls assert no
 * active DB transaction (ADR-008 invariant 6).
 */
public class MinioEvidenceStorage implements EvidenceStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioEvidenceStorage.class);
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final io.minio.MinioClient client;
    private final String bucket;

    public MinioEvidenceStorage(EvidenceStorageProperties properties) {
        this.client = io.minio.MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        this.bucket = properties.getBucket();
    }

    /** Idempotent bucket ensure; invoked by the storage configuration. */
    void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("created evidence bucket {} (private by default)", bucket);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Evidence storage unavailable at startup: " + e.getMessage(), e);
        }
    }

    @Override
    public StoredObject put(String key, byte[] content, String contentType) {
        assertNoTransaction();
        try {
            client.putObject(io.minio.PutObjectArgs.builder()
                    .bucket(bucket).object(key)
                    .stream(new java.io.ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
            return new StoredObject(sha256Hex(content), content.length);
        } catch (Exception e) {
            throw new EvidenceStorageException("PUT " + key + " failed", e);
        }
    }

    @Override
    public Optional<ObjectHead> head(String key) {
        assertNoTransaction();
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build());
            return Optional.of(new ObjectHead(stat.size()));
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (NO_SUCH_KEY.equals(code)) {
                return Optional.empty();
            }
            throw new EvidenceStorageException("HEAD " + key + " failed", e);
        } catch (Exception e) {
            throw new EvidenceStorageException("HEAD " + key + " failed", e);
        }
    }

    @Override
    public String presignedGet(String key, Duration ttl) {
        assertNoTransaction();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET)
                    .bucket(bucket).object(key)
                    .expiry((int) Math.min(ttl.toSeconds(), Integer.MAX_VALUE))
                    .build());
        } catch (Exception e) {
            throw new EvidenceStorageException("presign " + key + " failed", e);
        }
    }

    @Override
    public boolean delete(String key) {
        assertNoTransaction();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (NO_SUCH_KEY.equals(code)) {
                return false;
            }
            throw new EvidenceStorageException("DELETE " + key + " failed", e);
        } catch (Exception e) {
            throw new EvidenceStorageException("DELETE " + key + " failed", e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        assertNoTransaction();
        try {
            List<String> keys = new ArrayList<>();
            client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).build())
                    .forEach(item -> {
                        try {
                            keys.add(item.get().objectName());
                        } catch (Exception e) {
                            throw new EvidenceStorageException("LIST " + prefix + " failed", e);
                        }
                    });
            return List.copyOf(keys);
        } catch (Exception e) {
            if (e instanceof EvidenceStorageException storage) {
                throw storage;
            }
            throw new EvidenceStorageException("LIST " + prefix + " failed", e);
        }
    }

    /** Reads the whole object (used by retention/orphan reconciliation only). */
    public byte[] get(String key) {
        assertNoTransaction();
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new EvidenceStorageException("GET " + key + " failed", e);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Storage call attempted inside a DB transaction (ADR-08 invariant 6)");
        }
    }

    /** Raised when the object store cannot be reached or rejects a call (maps to 503). */
    public static class EvidenceStorageException extends RuntimeException {
        public EvidenceStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
