package com.quizopia.backend.media.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code quizopia.media.storage.*} (P4-D, ADR-009 §2.3). */
@ConfigurationProperties("quizopia.media.storage")
public class EvidenceStorageProperties {

    /** S3 endpoint of the private evidence bucket (MinIO). */
    private String endpoint = "http://localhost:9000";

    /** Access key — backend only; blank disables the storage bean. */
    private String accessKey;

    private String secretKey;

    private String bucket = "quizopia-evidence";

    /** Presigned GET lifetime (short-lived; every mint is audited). */
    private Duration presignTtl = Duration.ofSeconds(300);

    /** S3 endpoint AS REACHABLE FROM THE EGRESS CONTAINER (docker network),
     *  handed to egress start requests inline — the backend endpoint above
     *  may be a host-mapped URL the container cannot use. */
    private String egressEndpoint = "http://host.docker.internal:9000";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getEgressEndpoint() {
        return egressEndpoint;
    }

    public void setEgressEndpoint(String egressEndpoint) {
        this.egressEndpoint = egressEndpoint;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }
}
