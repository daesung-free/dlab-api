package com.dlab.domain.file.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 첨부파일 한 건.
 *
 * <h2>도메인마다 컬럼을 만들지 않는다</h2>
 * 성적표·재학증명서·사유 증빙·공지 이미지가 전부 같은 모양이라, 도메인마다
 * {@code file_url} 컬럼을 붙이면 <b>같은 검증·삭제·만료 처리를 그 수만큼 다시 짜게 된다.</b>
 * 대신 {@code ownerType}+{@code ownerId}로 어디에 붙었는지만 남긴다.
 *
 * <h2>★ 실제 파일을 지우지 않는다</h2>
 * soft delete 다. 물리 삭제하면 <b>"그때 무엇이 제출됐는가"에 답할 수 없다</b> — 성적표
 * 원본 확인이나 환불 다툼에서 근거가 되는 파일이다. S3 객체는 남고 행만 지워진 것으로
 * 표시되며, 보관 기간이 지난 것은 별도 정리 작업이 지운다(정책 미확정, §4).
 *
 * @param ownerType 붙은 대상의 종류. 도메인이 자기 이름을 넣는다({@code "ADMISSION_CONSULT"} 등).
 *                  <b>enum 으로 두지 않는다</b> — 새 도메인이 파일을 쓸 때마다 이 파일을
 *                  고치게 되고, 그러면 도메인이 서로를 알게 된다
 * @param ownerId   그 대상의 id. 아직 대상이 만들어지기 전에 올리는 경우가 있어 비어 있을 수 있다
 *                  (신청서 저장 전에 파일부터 붙이는 화면)
 */
@Getter
@Entity
@Table(name = "file_attachment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileAttachment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FileVisibility visibility;

    /** S3 객체 키. 버킷 안에서의 경로다 — 버킷 이름은 설정에 있고 여기 넣지 않는다. */
    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    /**
     * 올린 사람이 보던 파일명.
     *
     * <p>저장 키에는 UUID 를 쓰므로 이 값이 없으면 <b>내려받을 때 이름이
     * {@code 3f2a....pdf} 가 된다.</b> 한글·공백이 들어와도 그대로 보관한다.
     */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "owner_type", nullable = false, length = 50)
    private String ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    public FileAttachment(FileVisibility visibility, String objectKey, String originalName,
                          String contentType, long sizeBytes, String ownerType, Long ownerId) {
        this.visibility = visibility;
        this.objectKey = objectKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
    }

    /**
     * 대상에 붙인다.
     *
     * <p>신청서를 저장하기 전에 파일부터 올리는 화면이 있어, 업로드 시점에는 대상 id 가
     * 없을 수 있다. 저장이 끝나면 이 호출로 이어 붙인다.
     */
    public void attachTo(Long ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isPublicFile() {
        return visibility == FileVisibility.PUBLIC;
    }
}
