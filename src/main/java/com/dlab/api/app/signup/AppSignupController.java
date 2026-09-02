package com.dlab.api.app.signup;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.verification.PhoneVerificationService;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.service.ParentSignupService;
import com.dlab.domain.user.service.StudentSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/**
 * 앱 회원가입 — 휴대폰 인증 · 학부모 가입 (A-2).
 *
 * <p><b>인증 없이 호출되는 구획이다.</b> 가입 전이라 토큰이 없다 — SecurityConfig에서
 * {@code permitAll}로 열려 있다.
 *
 * <p><b>학생과 학부모는 흐름이 완전히 다르다.</b> 학생은 승인제(행정선생님 승인 전까지
 * 로그인 자체가 거부된다), 학부모는 비승인제로 즉시 이용 가능하다. 두 흐름을 하나로
 * 합치려 들지 말 것.
 *
 * <p>성적 입력은 가입에 포함되지 않는다 — 가입을 끝낸 뒤
 * {@code /api/v1/app/grades}에서 이어서 낸다({@link com.dlab.api.app.grade}).
 */
@Tag(name = "앱 · 회원가입 (A-2)")
@RestController
@RequestMapping("/api/v1/app/signup")
@RequiredArgsConstructor
public class AppSignupController {

    private final PhoneVerificationService phoneVerificationService;
    private final ParentSignupService parentSignupService;
    private final StudentSignupService studentSignupService;
    private final AcademyRepository academyRepository;

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

    /**
     * 학생 회원가입 — <b>승인 대기</b> 상태로 만들어진다.
     *
     * <p>학부모와 달리 여기서 끝이 아니다. 행정선생님이 승인해야 로그인이 열리고,
     * 그 전까지는 로그인 API가 {@code SIGNUP_PENDING}으로 거부한다.
     *
     * <p><b>가입 경로는 이것 하나뿐이다</b> — 관리자가 계정을 직접 만들어주는 API를
     * 추가하지 말 것. 승인 절차를 우회하는 두 번째 경로가 된다(0805 시트).
     *
     * <p>성적은 이어지는 {@code /api/v1/app/grades}에서 낸다.
     */
    @PostMapping("/student")
    public ApiResponse<SignupResponses.StudentSignupResult> signupStudent(
            @Valid @RequestBody SignupRequests.StudentSignup request) {
        var account = studentSignupService.signup(new StudentSignupService.Command(
                request.verificationToken(), request.name(), request.password(),
                request.academyId(), request.grade(), request.track(),
                request.birthDate(), request.gender(), request.schoolName(), request.address()));

        return ApiResponse.success(new SignupResponses.StudentSignupResult(
                account.getLoginId(), account.getStudent().getUniqueCode(), true));
    }

    /**
     * 지점 목록 — 가입 화면의 선택지.
     *
     * <p>토큰이 생기기 전에 필요해서 <b>인증 없이</b> 열려 있다. 지점명은 간판에 걸린
     * 공개 정보다 — 다만 이 응답에 운영정보(키오스크 자격증명·PG 코드)를 실지 말 것.
     */
    @GetMapping("/academies")
    public ApiResponse<List<SignupResponses.AcademyOption>> academies() {
        return ApiResponse.success(academyRepository.findAllActive().stream()
                .map(SignupResponses.AcademyOption::from).toList());
    }
}
