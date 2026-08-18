package com.dlab.domain.appconfig.entity;

/**
 * 코드에서 참조해야 하는 약관 코드.
 *
 * <p><b>enum이 아니다.</b> {@code terms.code}는 자유 문자열이라 관리자가 화면에서 얼마든지
 * 늘릴 수 있고(장학 종류만 해도 셋이다), 여기 있는 것은 그중 <b>코드가 동작을 갈라야 하는
 * 것들</b>뿐이다. 전체 목록이 아니므로 여기 없다고 잘못된 코드가 아니다.
 */
public final class TermsCode {

    private TermsCode() {}

    /**
     * 급식업체 개인정보 제3자 제공.
     *
     * <p>동의서가 <i>"급식 신청 시에만 급식업체에 정보 제공"</i>이라 <b>신청 시점에</b>
     * 확인해야 한다 — 가입 때 한 번 받고 끝나는 값이 아니다.
     */
    public static final String MEAL_THIRD_PARTY = "MEAL_THIRD_PARTY";
}
