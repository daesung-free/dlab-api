package com.dlab.api.admin.notice;

import jakarta.validation.constraints.NotNull;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeAuthorType;
import com.dlab.domain.notice.entity.NoticeScope;
import com.dlab.domain.notice.service.NoticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 공지 (F-4.11-3).
 *
 * <p><b>조회는 전체 공유, 작성은 범위별 제한</b>이다. 목록에는 전 지점 공지도 함께 나온다 —
 * 지점 관리자도 본사 공지를 봐야 한다.
 *
 * <p>범위별로 엔드포인트를 나눈 이유: 하나로 받고 {@code scope}를 본문에 넣으면
 * 대상 필드(반·학생)가 범위마다 달라 <b>어느 조합이 유효한지가 요청마다 흔들린다.</b>
 * 경로가 갈리면 필요한 값이 무엇인지 명확하다.
 */
@Tag(name = "관리자 · 공지 (F-4.11-3)")
@RestController
@RequestMapping("/api/v1/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NoticeService noticeService;

    /**
     * 공지 목록. <b>조회는 관리자 전체 공유</b>이고 작성만 역할별로 갈린다.
     *
     * <p>열람 수({@code readCount})를 함께 내린다 — 공지마다 세면 쿼리가 행 수만큼
     * 나가므로 ID를 모아 한 번에 읽는다.
     */
    @GetMapping
    public ApiResponse<List<NoticeResponse>> list(@CurrentAccount AuthPrincipal me,
                                                  @RequestParam(required = false) Short year) {
        var notices = noticeService.findForAdmin(me, year);
        var readCounts = noticeService.readCounts(
                notices.stream().map(Notice::getId).toList());

        return ApiResponse.success(notices.stream()
                .map(n -> NoticeResponse.of(n, readCounts.getOrDefault(n.getId(), 0L)))
                .toList());
    }

    /** 전 지점 공지 — <b>본사만.</b> */
    @PostMapping("/all")
    public ApiResponse<NoticeResponse> createForAll(@CurrentAccount AuthPrincipal me,
                                                    @Valid @RequestBody ContentRequest request) {
        return ApiResponse.success(NoticeResponse.from(
                noticeService.createForAll(me, request.title(), request.content())));
    }

    /** 지점 공지 — 지점관리자 이상. */
    @PostMapping("/branches/{academyId}")
    public ApiResponse<NoticeResponse> createForBranch(@CurrentAccount AuthPrincipal me,
                                                       @PathVariable Long academyId,
                                                       @Valid @RequestBody ContentRequest request) {
        return ApiResponse.success(NoticeResponse.from(
                noticeService.createForBranch(me, academyId, request.title(), request.content())));
    }

    /** 반 공지 — 그 반 담임 또는 지점관리자 이상. */
    @PostMapping("/classes/{classId}")
    public ApiResponse<NoticeResponse> createForClass(@CurrentAccount AuthPrincipal me,
                                                      @PathVariable Long classId,
                                                      @Valid @RequestBody ContentRequest request) {
        return ApiResponse.success(NoticeResponse.from(
                noticeService.createForClass(me, classId, request.title(), request.content())));
    }

    /** 개별 공지 — 그 학생의 담임 또는 지점관리자 이상. */
    @PostMapping("/students/{enrollmentId}")
    public ApiResponse<NoticeResponse> createForIndividual(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody ContentRequest request) {
        return ApiResponse.success(NoticeResponse.from(
                noticeService.createForIndividual(
                        me, enrollmentId, request.title(), request.content())));
    }

    /** <b>범위와 대상은 못 바꾼다</b> — 바꿀 수 있으면 권한 검사를 한 번만 통과하고 범위를 넓힐 수 있다. */
    @PutMapping("/{id}")
    public ApiResponse<NoticeResponse> update(@CurrentAccount AuthPrincipal me,
                                              @PathVariable Long id,
                                              @Valid @RequestBody NoticeUpdateRequest request) {
        return ApiResponse.success(NoticeResponse.from(noticeService.update(
                me, id, request.title(), request.content(),
                request.pinned(), request.banner(),
                request.publishedAt(), request.expiresAt())));
    }

    /**
     * 부분 수정 — <b>보내지 않은 필드는 그대로</b>.
     *
     * <p>상단 고정·배너 토글은 본문과 무관한 조작인데 {@code PUT}은 제목·내용을 요구해서,
     * 화면이 기존 값을 채워 보내야 했다. <b>그 사이 남이 본문을 고쳤으면 되돌아간다</b> —
     * 목록이 낡았을수록 확률이 올라간다.
     *
     * <p>⚠️ 예약·만료 시각을 <b>지우는 것</b>은 이 경로로 안 된다({@code null}이 "그대로"라서).
     * 지울 때는 {@code PUT}을 쓴다.
     */
    @PatchMapping("/{id}")
    public ApiResponse<NoticeResponse> patch(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long id,
                                             @Valid @RequestBody NoticePatchRequest request) {
        return ApiResponse.success(NoticeResponse.from(noticeService.patch(
                me, id, request.title(), request.content(),
                request.pinned(), request.banner(),
                request.publishedAt(), request.expiresAt())));
    }

    /** 공지 삭제(soft). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me, @PathVariable Long id) {
        noticeService.delete(me, id);
        return ApiResponse.empty();
    }

    public record ContentRequest(
            @NotBlank(message = "제목은 필수입니다.") @Size(max = 200) String title,
            @NotBlank(message = "내용은 필수입니다.") String content) {
    }

    /**
     * @param publishedAt 예약 발행. {@code null}이면 즉시 공개
     * @param banner      앱 홈 배너 노출
     */
    public record NoticeUpdateRequest(
            @NotBlank(message = "제목은 필수입니다.") @Size(max = 200) String title,
            @NotBlank(message = "내용은 필수입니다.") String content,
            @NotNull(message = "상단 고정 여부는 필수입니다.") Boolean pinned,
            @NotNull(message = "배너 노출 여부는 필수입니다.") Boolean banner,
            Instant publishedAt,
            Instant expiresAt) {
    }

    /** 전부 선택값이다 — 보내지 않은 것은 바뀌지 않는다. */
    public record NoticePatchRequest(
            @Size(max = 200) String title,
            String content,
            Boolean pinned,
            Boolean banner,
            Instant publishedAt,
            Instant expiresAt) {
    }

    /** @param authorType 행정(EMPLOYEE)과 담당선생님(TEACHER)은 다른 테이블이다 */
    public record NoticeResponse(
            Long id,
            NoticeScope scope,
            Long academyId,
            Long classId,
            String className,
            Long enrollmentId,
            String title,
            String content,
            NoticeAuthorType authorType,
            Long authorId,
            boolean pinned,
            boolean banner,
            Instant publishedAt,
            Instant expiresAt,
            Instant createdAt,
            /** 열람한 학생 수. 학부모 열람은 세지 않는다 — 학생이 봤는지가 기준이다 */
            long readCount) {

        public static NoticeResponse from(Notice n) {
            return of(n, 0L);
        }

        public static NoticeResponse of(Notice n, long readCount) {
            return new NoticeResponse(
                    n.getId(), n.getScope(),
                    n.getAcademy() == null ? null : n.getAcademy().getId(),
                    n.getClassMaster() == null ? null : n.getClassMaster().getId(),
                    n.getClassMaster() == null ? null : n.getClassMaster().getName(),
                    n.getEnrollment() == null ? null : n.getEnrollment().getId(),
                    n.getTitle(), n.getContent(),
                    n.getAuthorType(), n.getAuthorId(),
                    n.isPinned(), n.isBanner(),
                    n.getPublishedAt(), n.getExpiresAt(), n.getCreatedAt(), readCount);
        }
    }
}
