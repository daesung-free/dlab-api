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
}
