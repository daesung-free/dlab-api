package com.dlab.domain.file.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.repository.FileAttachmentRepository;
import com.dlab.integration.s3.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 첨부파일 업로드·조회.
 *
 * <h2>업로드는 전부 서버를 거친다</h2>
 * presigned 업로드(클라이언트가 S3 로 직접 PUT)를 쓰지 않는다. 그러면 <b>용량·형식 검증과
 * EXIF 제거를 클라이언트마다 다시 구현</b>해야 하고, 한 곳만 빠뜨리면 그때부터 검증되지
 * 않은 파일이 들어온다. 버킷 이름도 클라이언트가 알 필요가 없어진다.
 *
 * <h2>키 구조</h2>
 * <pre>{도메인}/{연도}/{대상id}/{uuid}.{확장자}</pre>
 * 연도를 넣는 이유는 <b>보관기간 정리</b> 때문이다 — "2026년 입학상담 첨부"를 접두어로
 * 한 번에 훑을 수 있어야 한다. 파일명에 UUID 를 쓰므로 같은 이름을 올려도 덮어쓰지 않는다.
 *
 * <p>⚠️ <b>원래 파일명을 키에 넣지 않는다.</b> 한글·공백·경로문자가 그대로 들어가면
 * URL 인코딩 문제가 생기고, 이름 자체가 개인정보인 경우도 있다({@code 홍길동_진단서.pdf}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAttachmentService {

    /**
     * 받는 형식. 입학상담 화면이 명시한 목록이다(jpg·jpeg·png·gif·pdf).
     *
     * <p><b>허용 목록으로 간다.</b> 금지 목록은 빠뜨린 확장자가 곧 구멍이 된다.
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "application/pdf");

    private final FileAttachmentRepository repository;
    private final FileStorage storage;
    private final StorageProperties properties;
    private final Clock clock;

    /**
     * 올린다.
     *
     * @param ownerId 대상이 아직 저장되기 전이면 {@code null}. 나중에 {@link #attach}로 잇는다
     */
    @Transactional
    public FileAttachment upload(FileVisibility visibility, String ownerType, Long ownerId,
                                 String originalName, String contentType, byte[] content) {
        validate(contentType, content);

        byte[] stored = ExifStripper.strip(content, contentType);
        String objectKey = objectKey(ownerType, ownerId, originalName);

        try (InputStream in = new ByteArrayInputStream(stored)) {
            storage.put(visibility, objectKey, in, stored.length, contentType);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일 업로드에 실패했습니다.");
        }

        FileAttachment saved = repository.save(new FileAttachment(
                visibility, objectKey, originalName, contentType, stored.length,
                ownerType, ownerId));
        log.info("첨부 업로드: id={}, owner={}:{}, {}bytes", saved.getId(), ownerType, ownerId,
                stored.length);
        return saved;
    }

    /** 대상이 저장된 뒤 이어 붙인다. */
    @Transactional
    public FileAttachment attach(Long attachmentId, Long ownerId) {
        FileAttachment file = load(attachmentId);
        file.attachTo(ownerId);
        return file;
    }

    @Transactional(readOnly = true)
    public List<FileAttachment> findByOwner(String ownerType, Long ownerId) {
        return repository.findByOwner(ownerType, ownerId);
    }

    /**
     * 볼 수 있는 주소.
     *
     * <p>공개 파일은 CloudFront 고정 주소, 비공개는 <b>짧은 presigned URL</b>이다.
     * 비공개 주소는 로그인 없이 열리므로 화면에 오래 남겨두지 말 것 — 만료되면 다시 받는다.
     */
    @Transactional(readOnly = true)
    public String url(Long attachmentId) {
        FileAttachment file = load(attachmentId);
        return file.isPublicFile()
                ? storage.publicUrl(file.getObjectKey())
                : storage.presignedGetUrl(file.getObjectKey(), properties.presignTtlOrDefault());
    }

    /**
     * 첨부를 뗀다.
     *
     * <p><b>S3 객체는 지우지 않는다.</b> "그때 무엇이 제출됐는가"가 성적표 원본 확인·환불
     * 다툼의 근거다. 보관기간이 지난 파일은 별도 정리 작업이 지운다(정책 미확정, §4).
     */
    @Transactional
    public void delete(Long attachmentId) {
        load(attachmentId).markDeleted();
    }

    private FileAttachment load(Long attachmentId) {
        return repository.findActiveById(attachmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
    }

    private void validate(String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일입니다.");
        }
        if (content.length > properties.maxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "파일은 %dMB 이하여야 합니다.".formatted(properties.maxFileSizeBytes() / 1024 / 1024));
        }
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "이미지(jpg·png·gif)와 PDF 만 올릴 수 있습니다.");
        }
    }

    /**
     * {@code {도메인}/{연도}/{대상id}/{uuid}.{확장자}}
     *
     * <p>대상 id 가 아직 없으면 {@code pending} 아래 둔다 — 연결되지 않은 채 남은 파일을
     * 나중에 찾아 정리할 수 있어야 한다.
     */
    private String objectKey(String ownerType, Long ownerId, String originalName) {
        String folder = ownerType.toLowerCase(Locale.ROOT).replace('_', '-');
        String owner = ownerId == null ? "pending" : String.valueOf(ownerId);
        return "%s/%d/%s/%s%s".formatted(
                folder, LocalDate.now(clock).getYear(), owner,
                UUID.randomUUID(), extensionOf(originalName));
    }

    /** 확장자가 없으면 빈 문자열. 확장자로 형식을 판정하지 않는다 — 판정은 contentType 이 한다. */
    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? "." + ext : "";
    }
}
