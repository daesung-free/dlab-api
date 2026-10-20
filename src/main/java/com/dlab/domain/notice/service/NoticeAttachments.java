package com.dlab.domain.notice.service;

import com.dlab.domain.file.service.FileAttachmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 첨부 조회 — <b>관리자·앱이 같은 것을 쓴다</b> (디랩 1019 확정).
 *
 * <h2>★ Notice 에 컬럼을 더하지 않는다</h2>
 * {@code file_attachment} 이 {@code (ownerType, ownerId)} 로 붙는 <b>범용 테이블</b>이다.
 * {@code image_url} 같은 칸을 새로 박으면 <b>여러 장을 못 담고</b> 파일 정리 기준도 갈라진다.
 *
 * <h2>★ 목록 응답에는 싣지 않는다</h2>
 * 상세에만 쓴다 — 목록에 넣으면 <b>공지 20건마다 첨부 조회가 따라붙는다.</b>
 *
 * <h2>⚠️ {@code ownerType} 문자열을 호출부마다 쓰지 않는다</h2>
 * {@code file_attachment.owner_type} 이 자유 문자열이라, 한 곳에서 {@code "NOTICE"} 를
 * 쓰고 다른 곳에서 {@code "notice"} 를 쓰면 <b>업로드는 됐는데 조회가 안 되는</b> 상태가
 * 되고 오류도 나지 않는다. 그래서 상수를 여기 한 곳에 둔다.
 */
@Component
@RequiredArgsConstructor
public class NoticeAttachments {

    /** 업로드할 때도 이 값을 쓴다 — 화면에 그대로 안내할 것. */
    public static final String OWNER_TYPE = "NOTICE";

    private final FileAttachmentService fileAttachmentService;

    @Transactional(readOnly = true)
    public List<View> of(Long noticeId) {
        return fileAttachmentService.findByOwner(OWNER_TYPE, noticeId).stream()
                .map(f -> new View(f.getId(), f.getOriginalName(), f.getContentType(),
                        f.getSizeBytes(), fileAttachmentService.url(f.getId())))
                .toList();
    }

    /**
     * @param url 볼 수 있는 주소. 공개 파일은 <b>고정 주소</b>(CloudFront)라 화면이 저장해
     *            써도 된다 — 공지 이미지는 공개로 올린다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "NoticeAttachmentResponse")
    public record View(Long id, String originalName, String contentType, long sizeBytes,
                       String url) {
    }
}
