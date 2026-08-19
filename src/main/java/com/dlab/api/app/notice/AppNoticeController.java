package com.dlab.api.app.notice;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeScope;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.user.service.AppScopeResolver;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 공지 조회.
 *
 * <p>전 지점 + 내 지점 + 내 반 + 나 개인이 <b>하나의 목록</b>으로 내려간다 —
 * 앱이 범위별로 네 번 부르고 합치면 정렬(고정 우선)이 클라이언트마다 갈린다.
 *
 * <p><b>예약 발행·만료는 서버가 거른다.</b> 앱이 시각을 비교하게 하면
 * 기기 시계가 틀어진 사용자에게 발표 전 공지가 보인다.
 */
@Tag(name = "앱 · 공지 조회")
@RestController
@RequestMapping("/api/v1/app/notices")
@RequiredArgsConstructor
public class AppNoticeController {

    private final NoticeService noticeService;
    private final AppScopeResolver scopeResolver;

    /**
     * 공지 목록.
     *
     * <p><b>내게 보이는 것만 내린다</b> — 전체공지와 내 반 공지가 합쳐져 나온다.
     *
     * @param studentId <b>학부모만</b> 쓴다. 계정 하나에 자녀가 여럿이라
     *                  누구 기준으로 볼지 서버가 정할 수 없다.
     *                  <b>등록 건 id가 아니라 학생 id다</b> — 다른 앱 API와 같은 값을 쓴다
     */
    @GetMapping
    public ApiResponse<List<AppNoticeResponse>> feed(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(noticeService.feed(enrollmentId).stream()
                .map(AppNoticeResponse::from).toList());
    }

    /** 앱 홈 배너 (F-4.12-3). */
    @GetMapping("/banners")
    public ApiResponse<List<AppNoticeResponse>> banners(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(noticeService.banners(enrollmentId).stream()
                .map(AppNoticeResponse::from).toList());
    }

    /**
     * 상세.
     *
     * <p><b>내게 보이는 공지인지 확인하고 내린다</b> — id만으로 열어주면
     * 번호를 바꿔가며 다른 반 공지를 읽을 수 있다.
     */
    @GetMapping("/{id}")
    public ApiResponse<AppNoticeResponse> readOne(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long id,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(
                AppNoticeResponse.from(noticeService.readOne(enrollmentId, id)));
    }

    /**
     * 앱 표시용. <b>작성자 정보는 내리지 않는다</b> — 학생·학부모에게 필요 없고,
     * 담당선생님 id가 앱으로 새어나갈 이유가 없다.
     */
    public record AppNoticeResponse(
            Long id,
            NoticeScope scope,
            String title,
            String content,
            boolean pinned,
            boolean banner,
            Instant postedAt) {

        public static AppNoticeResponse from(Notice n) {
            return new AppNoticeResponse(
                    n.getId(), n.getScope(), n.getTitle(), n.getContent(),
                    n.isPinned(), n.isBanner(),
                    // 예약 발행분은 발행 시각이 곧 게시일이다. 작성 시각을 내리면
                    // 미리 써둔 공지가 "며칠 전에 올라온 글"로 보인다
                    n.getPublishedAt() != null ? n.getPublishedAt() : n.getCreatedAt());
        }
    }
}
