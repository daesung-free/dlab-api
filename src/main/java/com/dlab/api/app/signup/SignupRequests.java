package com.dlab.api.app.signup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 가입 요청 DTO. */
public final class SignupRequests {

    /**
     * 전화번호 형식.
     *
     * <p>하이픈을 <b>요구한다</b> — 저장 형식이 {@code 010-1234-5678}로 통일돼 있고
     * (마스킹·DSA 호환 응답이 이 형식을 가정한다), 클라이언트마다 다르게 보내면
     * 같은 사람이 두 번 가입된다.
     */
    private static final String PHONE_REGEX = "^01[016789]-\\d{3,4}-\\d{4}$";

    private SignupRequests() {
    }

    public record PhoneVerification(
            @NotBlank(message = "휴대폰 번호는 필수입니다.")
            @Pattern(regexp = PHONE_REGEX, message = "휴대폰 번호 형식이 올바르지 않습니다. (예: 010-1234-5678)")
            String phone) {
    }

    public record ConfirmVerification(
            @NotBlank(message = "휴대폰 번호는 필수입니다.")
            @Pattern(regexp = PHONE_REGEX, message = "휴대폰 번호 형식이 올바르지 않습니다.")
            String phone,
            @NotBlank(message = "인증번호는 필수입니다.")
            @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자입니다.")
            String code) {
    }

    /**
     * 학부모 가입.
     *
     * <p>비밀번호 규칙은 {@code @Size}가 아니라
     * {@link com.dlab.common.security.PasswordPolicy}가 검사한다 — 정책이 미확정이라
     * 한 곳에 모여 있어야 확정 시 한 번에 바뀐다.
     */
    public record ParentSignup(
            @NotBlank(message = "휴대폰 인증이 필요합니다.") String verificationToken,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @NotBlank(message = "비밀번호는 필수입니다.") String password,
            @NotBlank(message = "학생 고유ID는 필수입니다.") @Size(max = 20) String studentUniqueCode) {
    }

    /** 자녀 추가 연결(다자녀). 이미 로그인한 상태라 인증 토큰이 필요 없다. */
    public record LinkChild(
            @NotBlank(message = "학생 고유ID는 필수입니다.") @Size(max = 20) String studentUniqueCode) {
    }
}
