package com.dlab.integration.s3;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.service.FileStorage;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 저장소.
 *
 * <h2>★ 설정이 없어도 기동한다</h2>
 * 로컬·CI 에는 자격증명이 없다. 여기서 기동을 막으면 파일과 무관한 작업까지 전부 멈춘다 —
 * 대신 <b>기동 시 경고</b>를 남기고, 실제로 올리려 할 때 실패시킨다.
 * {@code LoggingSmsSender} 와 같은 판단이되 <b>성공한 척하지는 않는다</b> — 문자는 안 가도
 * 다시 보내면 되지만, 파일은 "올렸는데 없는" 상태가 나중에 발견된다.
 *
 * <h2>클라이언트를 지연 생성한다</h2>
 * 설정이 비어 있으면 {@link S3Client} 를 만들 수조차 없다(리전·자격증명 필수). 그래서
 * 필드가 아니라 첫 사용 시점에 만든다.
 */
@Slf4j
@Component
public class S3FileStorage implements FileStorage {

    private final StorageProperties properties;
    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public S3FileStorage(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warnIfUnconfigured() {
        if (!properties.configured()) {
            log.warn("""
                    [파일 저장소] S3 설정이 비어 있어 업로드·다운로드가 동작하지 않는다.
                    필요한 값: S3_BUCKET_PUBLIC · S3_BUCKET_PRIVATE · AWS_ACCESS_KEY_ID · AWS_SECRET_ACCESS_KEY
                    (운영에서 이 경고가 보이면 .env 와 compose 의 app.environment 를 함께 확인할 것)""");
        }
    }

    @Override
    public void put(FileVisibility visibility, String objectKey, InputStream content, long size,
                    String contentType) {
        client().putObject(PutObjectRequest.builder()
                        .bucket(bucket(visibility))
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength(size)
                        .build(),
                RequestBody.fromInputStream(content, size));
    }

    @Override
    public void delete(FileVisibility visibility, String objectKey) {
        client().deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket(visibility))
                .key(objectKey)
                .build());
    }

    @Override
    public String presignedGetUrl(String objectKey, Duration ttl) {
        var presigned = presigner().presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket(FileVisibility.PRIVATE))
                        .key(objectKey)
                        .build())
                .build());
        return presigned.url().toString();
    }

    /**
     * 공개 파일 주소.
     *
     * <p><b>S3 주소가 아니라 CloudFront 주소를 준다.</b> 공개 버킷도 직접 접근은 막혀 있어
     * S3 주소로는 403 이 난다.
     */
    @Override
    public String publicUrl(String objectKey) {
        require();
        String base = properties.publicBaseUrl();
        if (base == null || base.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_NOT_CONFIGURED,
                    "공개 파일 주소(S3_PUBLIC_BASE_URL)가 설정되지 않았습니다.");
        }
        return base.endsWith("/") ? base + objectKey : base + "/" + objectKey;
    }

    private String bucket(FileVisibility visibility) {
        require();
        return visibility == FileVisibility.PUBLIC
                ? properties.publicBucket() : properties.privateBucket();
    }

    private void require() {
        if (!properties.configured()) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_NOT_CONFIGURED);
        }
    }

    private S3Client client() {
        require();
        S3Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    client = local = S3Client.builder()
                            .region(Region.of(properties.region()))
                            .credentialsProvider(credentials())
                            .build();
                }
            }
        }
        return local;
    }

    private S3Presigner presigner() {
        require();
        S3Presigner local = presigner;
        if (local == null) {
            synchronized (this) {
                local = presigner;
                if (local == null) {
                    presigner = local = S3Presigner.builder()
                            .region(Region.of(properties.region()))
                            .credentialsProvider(credentials())
                            .build();
                }
            }
        }
        return local;
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }

    /** 테스트·운영 점검용. 설정된 공개 주소의 호스트만 돌려준다(키는 노출하지 않는다). */
    public String publicHost() {
        String base = properties.publicBaseUrl();
        return base == null || base.isBlank() ? null : URI.create(base).getHost();
    }
}
