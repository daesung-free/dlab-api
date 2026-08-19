package com.dlab.api.app.signup;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.verification.PhoneVerificationService;
import com.dlab.domain.user.service.ParentSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 회원가입 — 휴대폰 인증 · 학부모 가입 (A-2).
 *
 * <p><b>인증 없이 호출되는 구획이다.</b> 가입 전이라 토큰이 없다 — SecurityConfig에서
 * {@code permitAll}로 열려 있다.
 *
 * <p><b>학생 가입은 여기 없다.</b> 가입 시 내신 성적 입력이 필수인데 <b>입력 양식이
 * 미확정</b>이라(§4 블로커), 임의로 정하면 확정 후 스키마와 화면을 다시 만들게 된다.
 * 학부모 가입은 내신과 무관해서 먼저 열었다.
 */
@Tag(name = "앱 · 회원가입 (A-2)")
@RestController
@RequestMapping("/api/v1/app/signup")
@RequiredArgsConstructor
public class AppSignupController {

    private final PhoneVerificationService phoneVerificationService;
    private final ParentSignupService parentSignupService;

    /**
     * 인증번호 발송.
     *
     * <p>⚠️ 발송 업체가 미확정이라 현재는 로그로만 남는다({@code LoggingSmsSender}).
     * 응답은 200이므로 화면상으로는 정상으로 보인다.
     */
    @PostMapping("/phone-verifications")
    public ApiResponse<Void> requestCode(@Valid @RequestBody SignupRequests.PhoneVerification request) {
        phoneVerificationService.requestCode(request.phone());
        return ApiResponse.empty();
    }

    /** 인증번호 확인 → 가입 요청에 실을 인증 토큰 발급. */
    @PostMapping("/phone-verifications/confirm")
    public ApiResponse<SignupResponses.VerificationToken> confirmCode(
            @Valid @RequestBody SignupRequests.ConfirmVerification request) {
        return ApiResponse.success(new SignupResponses.VerificationToken(
                phoneVerificationService.confirm(request.phone(), request.code())));
    }

    /**
     * 학부모 회원가입. 승인 절차 없이 즉시 가입 완료된다(학생과 다름).
     *
     * <p>전화번호가 아니라 <b>인증 토큰</b>을 받는다 — 번호를 그대로 받으면 인증을 건너뛸 수 있다.
     */
    @PostMapping("/parent")
    public ApiResponse<SignupResponses.SignupResult> signupParent(
            @Valid @RequestBody SignupRequests.ParentSignup request) {
        var account = parentSignupService.signup(request.verificationToken(), request.name(),
                request.password(), request.studentUniqueCode());
        return ApiResponse.success(new SignupResponses.SignupResult(account.getLoginId()));
    }
}
