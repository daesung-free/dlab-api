package com.dlab.api.app.signup;

import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

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

    /**
     * 학생 가입.
     *
     * <p><b>성적은 여기 없다.</b> 가입과 같은 요청으로 받으면 시험 3회차 × 과목 6개짜리
     * 긴 입력이 실려서, 중간에 실패했을 때 휴대폰 인증부터 다시 해야 한다.
     * 가입을 끝내고 {@code /api/v1/app/grades}로 이어서 낸다.
     *
     * <p>{@code academyId}는 학생이 고른다 — 지점 목록은
     * {@code GET /api/v1/app/signup/academies}가 인증 없이 내려준다.
     */
    public record StudentSignup(
            @NotBlank(message = "휴대폰 인증이 필요합니다.") String verificationToken,
            @NotBlank(message = "이름은 필수입니다.") @Size(max = 20) String name,
            @NotBlank(message = "비밀번호는 필수입니다.") String password,
            @NotNull(message = "지점은 필수입니다.") Long academyId,
            @NotNull(message = "학년은 필수입니다.") GradeType grade,
            TrackType track,
            LocalDate birthDate,
            @Pattern(regexp = "^[MF]$", message = "성별은 M 또는 F입니다.") String gender,
            @Size(max = 64) String schoolName,
            @Size(max = 200) String address) {
    }

    /** 자녀 추가 연결(다자녀). 이미 로그인한 상태라 인증 토큰이 필요 없다. */
    public record LinkChild(
            @NotBlank(message = "학생 고유ID는 필수입니다.") @Size(max = 20) String studentUniqueCode) {
    }
}
