package com.dlab.api.admin.readiness;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.readiness.ReadinessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 운영 준비 상태 점검.
 *
 * <h2>언제 보나</h2>
 * <ul>
 *   <li><b>컷오버 직전</b> — 지점·마스터가 다 들어갔는지</li>
 *   <li><b>해가 바뀌기 전</b> — 교습일수·가격·성적 양식이 연도별이라 <b>1월 1일에 청구와
 *       가입이 막힌다</b></li>
 *   <li>지점이 새로 열릴 때</li>
 * </ul>
 *
 * <h2>왜 필요한가</h2>
 * 빠진 설정은 대부분 <b>화면에서 보이지 않는다</b> — 승인 정책이 없으면 신청이 거절되고,
 * 사이트코드가 없으면 청구까지는 되는데 결제 링크에서만 실패한다. 체크리스트를 사람이
 * 읽어야만 걸러지던 것을 한 번에 본다.
 */
@Tag(name = "관리자 · 운영 준비 상태 점검")
@RestController
@RequestMapping("/api/v1/admin/readiness")
@RequiredArgsConstructor
public class AdminReadinessController {

    private final ReadinessService readinessService;

    /**
     * 점검 결과.
     *
     * <p>{@code blockerCount} 가 0 이어야 그 해 운영이 가능하다. {@code WARNING} 은 돌기는
     * 도는데 <b>조용히 틀린 값</b>으로 도는 것이라, 급하지 않을 뿐 무시하면 나중에 원인을
     * 찾기 어렵다.
     *
     * @param year 점검할 연도. <b>내년을 미리 넣어볼 수 있다</b> — 연말에 확인하라고 받는다
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<ReadinessService.Report> check(@RequestParam short year) {
        return ApiResponse.success(readinessService.check(year));
    }
}
