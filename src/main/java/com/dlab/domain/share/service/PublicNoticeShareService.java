package com.dlab.domain.share.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.file.service.FileAttachmentService;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.repository.NoticeRepository;
import com.dlab.domain.notice.service.NoticeAttachments;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.share.entity.PublicShareToken;
import com.dlab.domain.share.entity.ShareTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 안내문 — 「등록비 안내」 알림톡 버튼이 여는 페이지.
 *
 * <h2>★ 없는 토큰·만료 토큰도 「열 수 없다」 하나로 내린다</h2>
 * {@link View#unavailable} 가 그 응답이다. 404 로 내리면 <b>토큰을 두드려 보는 쪽에
 * 「이건 있다/없다」 힌트</b>가 된다 — 도어락 공개 경로가 같은 이유로 거절도 200 이다.
 *
 * <h2>★ 링크 기한은 공지의 게시 종료와 같다</h2>
 * 따로 두면 <b>공지는 내렸는데 링크로는 열리는</b> 상태가 된다. 등록비 안내처럼 기한이
 * 없는 공지는 링크도 무기한이다.
 */
@Service
@RequiredArgsConstructor
public class PublicNoticeShareService {

    /** 열 수 없을 때의 문구. <b>이유를 가르지 않는다</b> — 없음·만료·미게시가 전부 같다. */
    /**
     * 열 수 없을 때 화면에 그대로 띄우는 문장.
     *
     * <h2>★★ 세 경우에 같은 문장을 쓴다 — 갈라 주면 그 차이가 정보가 된다</h2>
     * 없는 토큰 · 기간 지남 · 삭제된 공지를 <b>구분해 알려 주면 주소를 찔러 보는 쪽이
     * 살아 있는 주소를 추려낼 수 있다.</b> 그래서 셋을 <b>나열</b>하되 어느 것인지는
     * 말하지 않는다 — 받는 사람에게 「다음에 할 일」(학원 문의)은 주고 구분은 주지 않는다.
     *
     * <p>⚠️ <b>경우마다 다른 문장을 넣지 말 것.</b> 응답 코드를 200 으로 통일한 것과 같은
     * 이유다(404 로 내리면 그 자체가 「있다/없다」를 알려 준다).
     *
     * <p>줄바꿈은 {@code \n} 으로 넘긴다 — 화면이 그대로 살린다.
     */
    public static final String UNAVAILABLE_MESSAGE =
            """
            안내문을 확인할 수 없습니다.
            유효하지 않은 링크이거나, 기간이 지났거나, 삭제된 안내문입니다.
            자세한 내용은 학원으로 문의해 주시기 바랍니다.""";

    private final PublicShareTokenService tokenService;
    private final NoticeRepository noticeRepository;
    private final NoticeService noticeService;
    private final NoticeAttachments noticeAttachments;
    private final FileAttachmentService fileAttachmentService;
    private final Clock clock;

    /**
     * 공유 링크 발급 (관리자).
     *
     * <p>★ <b>권한 검사는 {@link NoticeService#findOneForAdmin} 에 맡긴다</b> — 지점 범위
     * 판정을 여기서 다시 짜면 한쪽만 고쳐질 수 있고, 남의 지점 공지로 공개 링크를
     * 만들 수 있게 된다.
     *
     * <p>⚠️ <b>기한을 인자로 받지 않는다</b>(설계 결정 5). 공지의 게시 종료를 그대로 쓴다.
     *
     * <p>★★ <b>같은 공지면 같은 토큰이 나온다.</b> 누를 때마다 새로 만들면 <b>이미 보낸
     * 알림톡의 링크가 죽는다</b> — 알림톡은 보낸 뒤 본문을 바꿀 수 없다.
     * 게시 종료가 늘어났으면 <b>같은 토큰의 기한만</b> 옮긴다({@code SYNC}).
     */
    @Transactional
    public PublicShareToken share(AuthPrincipal me, Long noticeId) {
        Notice notice = noticeService.findOneForAdmin(me, noticeId);
        return tokenService.issueOrReuse(ShareTargetType.NOTICE, notice.getId(),
                notice.getAcademy(), notice.getYear(), notice.getExpiresAt(),
                PublicShareTokenService.ExpiryOnReuse.SYNC);
    }

    /** 공개 조회. */
    @Transactional(readOnly = true)
    public View view(String token) {
        Optional<Notice> found = resolveNotice(token);
        if (found.isEmpty()) {
            return View.unavailable();
        }
        Notice notice = found.get();
        return new View(true, notice.getTitle(), notice.getContent(),
                notice.getAcademy() == null ? null : notice.getAcademy().getAcadNm(),
                postedAt(notice), noticeAttachments.of(notice.getId()), null);
    }

    /**
     * 첨부 내려받기 주소.
     *
     * <p>★★ <b>그 토큰에 딸린 공지의 첨부만 내려간다.</b> {@code fileId} 만 바꿔 남의 파일을
     * 받을 수 있으면 안 된다 — {@code file_attachment} 는 성적표·재학증명서까지 담는
     * 범용 테이블이라, 소유 검사가 없으면 공개 토큰 하나로 <b>개인 서류가 전부 열린다.</b>
     *
     * <p>★ <b>「없는 파일」과 「남의 파일」을 같은 응답으로 거절한다.</b> 갈라서 내리면
     * id 를 세어 올리며 어느 파일이 존재하는지 알아낼 수 있다.
     */
    @Transactional(readOnly = true)
    public String fileUrl(String token, Long fileId) {
        Notice notice = resolveNotice(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND,
                        "파일을 찾을 수 없습니다."));

        boolean mine = fileAttachmentService
                .findByOwner(NoticeAttachments.OWNER_TYPE, notice.getId()).stream()
                .anyMatch(f -> f.getId().equals(fileId));
        if (!mine) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "파일을 찾을 수 없습니다.");
        }
        return fileAttachmentService.url(fileId);
    }

    /**
     * 토큰 → 공지. <b>열 수 없으면 비어 있다.</b>
     *
     * <p>토큰 만료 외에 <b>공지 자체의 공개 여부</b>도 본다 — 예약 발행 시각 전이거나
     * 게시가 끝난 공지는 링크로도 열리지 않아야 한다. 토큰 기한이 공지의 종료를
     * 따라가지만, 발급 뒤에 공지의 종료 시각이 바뀔 수 있어 여기서 한 번 더 본다.
     */
    private Optional<Notice> resolveNotice(String token) {
        Instant now = Instant.now(clock);
        return tokenService.resolve(token, ShareTargetType.NOTICE)
                .flatMap(t -> noticeRepository.findById(t.getTargetId()))
                .filter(n -> !n.isDeleted())
                .filter(n -> n.getPublishedAt() == null || !now.isBefore(n.getPublishedAt()))
                .filter(n -> n.getExpiresAt() == null || now.isBefore(n.getExpiresAt()));
    }

    /** 게시 시각. 예약 발행이 없으면 작성 시각이다 — 화면에 날짜가 비면 안 된다. */
    private Instant postedAt(Notice notice) {
        return notice.getPublishedAt() != null ? notice.getPublishedAt() : notice.getCreatedAt();
    }

    /**
     * @param available {@code false} 면 나머지는 전부 {@code null} 이고 {@code message} 만 있다
     * @param academyName 전 지점 공지는 {@code null}
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublicNoticeView")
    public record View(boolean available, String title, String content, String academyName,
                       Instant postedAt, List<NoticeAttachments.View> attachments,
                       String message) {

        static View unavailable() {
            return new View(false, null, null, null, null, null, UNAVAILABLE_MESSAGE);
        }
    }
}
