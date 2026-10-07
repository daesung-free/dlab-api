package com.dlab.api.app.user;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.service.AppScopeResolver;
import com.dlab.domain.user.service.ParentSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 마이페이지.
 *
 * <h2>★ 학생 고유ID가 여기에만 있다</h2>
 * 학부모가 자녀를 연결하는 <b>유일한 수단</b>이고(문자 발송·인쇄 등 다른 전달 경로가 없다),
 * 노출 위치도 마이페이지 하나로 정해져 있다. 이 화면이 없으면 학부모 가입이 성립하지 않는다.
 *
 * <p>이 ID를 아는 사람이면 학부모 본인 확인 없이 연결된다는 점은 클라이언트가 인지하고
 * 감수한 부분이다 — <b>추가 검증 로직을 임의로 만들지 말 것.</b>
 *
 * <p>자녀 <b>목록</b>은 {@code /api/v1/app/me/children}이 이미 내린다. 여기서 또 내리면
 * 자녀를 추가·해제했을 때 두 화면이 서로 다른 목록을 보여준다.
 */
@Tag(name = "앱 · 마이페이지")
@RestController
@RequestMapping("/api/v1/app/me")
@RequiredArgsConstructor
public class AppMyPageController {

    private final AppScopeResolver scopeResolver;
    private final ParentSignupService parentSignupService;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.user.service.AppWithdrawalService withdrawalService;

    /**
     * 내 정보.
     *
     * <p>학생과 학부모가 <b>다른 내용</b>을 본다 — 학생은 자기 고유ID·학번·반이고,
     * 학부모는 본인 연락처와 연결된 자녀 수다.
     */
    @GetMapping
    public ApiResponse<MyPageResponse> me(@CurrentAccount AuthPrincipal me) {
        Account account = scopeResolver.account(me.accountId());

        if (account.getStudent() != null) {
            StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), null);
            String className = classAssignmentRepository
                    .findActiveFixedByEnrollmentId(enrollment.getId())
                    .map(a -> a.getClassMaster().getName())
                    .orElse(null);
            return ApiResponse.success(MyPageResponse.ofStudent(account, enrollment, className));
        }

        if (account.getGuardian() != null) {
            int childCount = parentSignupService.children(account.getGuardian().getId()).size();
            return ApiResponse.success(MyPageResponse.ofGuardian(account, childCount));
        }

        throw new BusinessException(ErrorCode.FORBIDDEN, "학생·학부모 계정만 이용할 수 있습니다.");
    }

    /**
     * 회원 탈퇴 (App Store 5.1.1(v) · Google Play 필수 요건).
     *
     * <p><b>즉시</b> 로그인이 막히고, 들고 있던 토큰도 그 자리에서 무효가 된다.
     * 앱이 수집한 것(푸시 토큰·알림 수신 설정)은 지워지고, 학부모면 자녀 연결이 끊긴다.
     *
     * <p>★ <b>원생 기록(출결·수납·상벌점)은 남는다.</b> 학원이 계약·학원법 근거로 보유하는
     * 것이고, 같이 지우면 지점 정산과 과거 통계가 소급해서 바뀐다. 보관기간 경과분 파기는
     * 정책 확정 후 배치로 붙는다 — <b>화면에 그 사실을 안내해야 한다.</b>
     *
     * <p><b>두 번 눌러도 된다</b> — 이미 탈퇴한 계정이면 {@code alreadyWithdrawn=true}로
     * 그대로 성공한다. 두 번째를 실패로 만들면 화면은 "탈퇴가 안 됐다"로 보이는데
     * 실제로는 이미 된 상태다.
     */
    @DeleteMapping
    public ApiResponse<com.dlab.domain.user.service.AppWithdrawalService.Result> withdraw(
            @CurrentAccount AuthPrincipal me,
            @RequestBody(required = false) WithdrawRequest request,
            @RequestHeader(value = org.springframework.http.HttpHeaders.AUTHORIZATION,
                    required = false) String authorization) {

        return ApiResponse.success(withdrawalService.withdraw(
                me.accountId(),
                request == null ? null : request.reason(),
                bearer(authorization)));
    }

    /** {@code Bearer } 를 떼고 토큰만 넘긴다 — 블랙리스트가 토큰 문자열로 대조한다. */
    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String token = authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
        return token.isEmpty() ? null : token;
    }

    /** @param reason 선택. 개선 근거로만 쓰고 <b>재가입을 막는 데 쓰지 않는다</b> */
    public record WithdrawRequest(
            @jakarta.validation.constraints.Size(max = 200) String reason) {
    }
}
