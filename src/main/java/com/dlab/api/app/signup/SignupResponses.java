package com.dlab.api.app.signup;

/** 가입 응답 DTO. */
public final class SignupResponses {

    private SignupResponses() {
    }

    /**
     * @param verificationToken 가입 요청에 실어 보낼 인증 토큰. 30분 유효, <b>한 번만 쓸 수 있다</b>
     */
    public record VerificationToken(String verificationToken) {
    }

    /**
     * @param loginId 발급된 로그인 아이디(= 인증한 전화번호). 가입 직후 로그인 화면에
     *                채워주기 위해 돌려준다 — 토큰은 주지 않는다(로그인을 한 번 거치게 한다)
     */
    public record SignupResult(String loginId) {
    }

    /**
     * 학생 가입 결과.
     *
     * <p>{@code uniqueCode}는 <b>승인 대기 중에도</b> 내려준다. 학부모가 이 값으로
     * 연결하는데, 학생 승인까지 기다리게 하면 학부모 가입이 같이 막힌다.
     * 여기서만 내려주는 이유는 {@link #SignupResult} 참고 — 무인증 조회 API로 열면
     * 전화번호만 넣어보는 것으로 남의 고유ID를 얻을 수 있다.
     *
     * @param pendingApproval 항상 {@code true}. 학생은 승인 전 로그인 자체가 거부되므로
     *                        앱이 곧바로 "승인 대기" 안내를 띄워야 한다
     */
    public record StudentSignupResult(String loginId, String uniqueCode, boolean pendingApproval) {
    }

    /** 가입 화면의 지점 선택지. 운영정보는 싣지 않는다 — 무인증으로 열리는 목록이다. */
    public record AcademyOption(Long academyId, String name) {

        public static AcademyOption from(com.dlab.domain.user.entity.Academy academy) {
            return new AcademyOption(academy.getId(), academy.getAcadNm());
        }
    }
}
