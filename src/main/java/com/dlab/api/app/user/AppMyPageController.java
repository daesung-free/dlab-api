package com.dlab.api.app.user;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.AppScopeResolver;
import com.dlab.domain.user.service.ParentSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RestController
@RequestMapping("/api/v1/app/me")
@RequiredArgsConstructor
public class AppMyPageController {

    private final AppScopeResolver scopeResolver;
    private final ParentSignupService parentSignupService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ClassAssignmentRepository classAssignmentRepository;

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
            StudentEnrollment enrollment = enrollmentRepository
                    .findCurrentByStudentId(account.getStudent().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
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
}
