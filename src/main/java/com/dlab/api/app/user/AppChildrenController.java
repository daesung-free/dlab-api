package com.dlab.api.app.user;

import com.dlab.api.app.signup.SignupRequests;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.service.ParentSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 — 자녀 목록 · 자녀 추가 연결 (A-2 · A-20).
 *
 * <p>앱의 <b>자녀 전환 UI</b>가 이 목록을 쓴다. 계정은 하나이고 자녀만 바꿔 끼우는 구조라
 * (자녀 수만큼 계정을 나누지 않는다 — CLAUDE.md §3), 전환은 클라이언트 상태일 뿐
 * 서버 세션이 아니다. 그래서 <b>조회 API마다 "내 자녀가 맞는지"를 다시 검사</b>해야 한다
 * ({@code ParentSignupService.requireMyChild}).
 */
@Tag(name = "앱 · 자녀 연결 (A-2 · A-20)")
@RestController
@RequestMapping("/api/v1/app/me/children")
@RequiredArgsConstructor
public class AppChildrenController {

    private final ParentSignupService parentSignupService;
    private final AccountRepository accountRepository;

    /**
     * 연결된 자녀 목록 (A-2 · A-20).
     *
     * <p>학부모는 <b>계정 하나에 자녀 여럿</b>을 연결한다. 앱은 이 목록으로 자녀 전환 UI를
     * 그리고, 다른 API에 {@code studentId}로 누구 기준인지 알려준다.
     */
    @GetMapping
    public ApiResponse<List<ChildResponse>> children(@CurrentAccount AuthPrincipal me) {
        return ApiResponse.success(parentSignupService.children(guardianId(me)).stream()
                .map(ChildResponse::from).toList());
    }

    /** 자녀 추가 연결(다자녀). 학생 고유ID만 있으면 되고 재인증은 요구하지 않는다. */
    @PostMapping
    public ApiResponse<ChildResponse> linkChild(@CurrentAccount AuthPrincipal me,
                                                @Valid @RequestBody SignupRequests.LinkChild request) {
        parentSignupService.linkChild(guardianId(me), request.studentUniqueCode());
        // 방금 연결한 자녀만 따로 만들지 않고 목록에서 찾는다 — 등록 건 정보가 같은 경로로 채워진다
        return ApiResponse.success(parentSignupService.children(guardianId(me)).stream()
                .filter(c -> c.student().getUniqueCode().equals(request.studentUniqueCode()))
                .map(ChildResponse::from)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_CODE_NOT_FOUND)));
    }

    /**
     * 계정 → 학부모 식별자.
     *
     * <p>{@code AuthPrincipal}이 들고 있는 건 {@code accountId}뿐이라 한 번 더 읽는다.
     * 학부모가 아닌 계정이 호출하면 여기서 막힌다 — 경로만 알면 학생 계정으로도
     * 호출할 수 있기 때문이다.
     */
    private Long guardianId(AuthPrincipal me) {
        Account account = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getAccountType() != AccountType.PARENT || account.getGuardian() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "학부모 계정만 사용할 수 있습니다.");
        }
        return account.getGuardian().getId();
    }
}
