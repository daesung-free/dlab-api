package com.dlab.api.admin.appaccount;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.user.service.AppAccountAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 웹 — 앱 계정 관리 (F-4.12-1).
 *
 * <p><b>계정 생성 엔드포인트를 두지 않는다.</b> 2026-08-05 시트 확정으로
 * <i>"관리자가 계정을 신규 생성하는 경로는 없음"</i>이 명시됐다 — 학생은 앱 자가가입 후
 * 관리자 승인이 유일한 경로다. 승인/반려와 온보딩 조회는 A-2 작업에서 붙인다.
 *
 * <p>경로는 시트의 {@code /api/v1/app-accounts}가 아니라 {@code /api/v1/admin/app-accounts}다.
 * 관리자 웹이 호출하는 API는 예외 없이 {@code /admin} 아래에 둔다(CLAUDE.md §5).
 */
@RestController
@RequestMapping("/api/v1/admin/app-accounts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminAppAccountController {

    private final AppAccountAdminService appAccountAdminService;

    /** 로그인 실패 누적 잠금 해제. 자동 해제는 없으므로 이 호출이 유일한 해제 수단이다. */
    @PostMapping("/{accountId}/unlock")
    public ApiResponse<Void> unlock(@PathVariable Long accountId) {
        appAccountAdminService.unlock(accountId);
        return ApiResponse.empty();
    }

    /**
     * 임시 비밀번호 재발급 (분실 시에 한함).
     *
     * <p><b>평문이 응답에 한 번만 실린다.</b> 저장하지 않으므로 관리자가 이 응답을 놓치면
     * 다시 발급해야 한다 — 되짚어 볼 수 있게 만들면 그 자체가 유출 경로가 된다.
     */
    @PostMapping("/{accountId}/temporary-password")
    public ApiResponse<TemporaryPasswordResponse> reissueTemporaryPassword(
            @PathVariable Long accountId) {
        return ApiResponse.success(new TemporaryPasswordResponse(
                appAccountAdminService.reissueTemporaryPassword(accountId)));
    }

    /**
     * @param temporaryPassword 평문 임시 비밀번호. 이 응답 이후로는 어디에도 남지 않는다
     */
    public record TemporaryPasswordResponse(String temporaryPassword) {
    }
}
