package com.dlab.common.storage;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크 저장 — 임시 구현.
 *
 * <p>⚠️ <b>서버가 여러 대면 업로드한 서버에서만 파일이 보인다.</b> 오토스케일링 전제라
 * 운영에서는 S3 구현체로 갈아끼워야 한다. 기동 시 경고를 남기는 이유다.
 *
 * <p>실제 구현체를 {@code realFileStorage} 이름으로 등록하면 이 목업은 물러난다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "realFileStorage")
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${storage.local.root:./storage}") String root) {
        this.root = Path.of(root);
    }

    @PostConstruct
    void warn() {
        log.warn("⚠️ 파일 저장이 로컬 디스크다({}) — 서버가 여러 대면 업로드한 서버에서만 보인다. "
                + "운영 전에 S3 구현체를 등록할 것", root.toAbsolutePath());
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        try {
            Path target = root.resolve(key).normalize();
            // 키에 ../ 가 섞이면 루트 밖으로 나간다
            if (!target.startsWith(root.normalize())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "잘못된 저장 경로입니다.");
            }
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return key;
        } catch (IOException e) {
            log.error("파일 저장 실패: key={}", key, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일 저장에 실패했습니다.");
        }
    }
}
