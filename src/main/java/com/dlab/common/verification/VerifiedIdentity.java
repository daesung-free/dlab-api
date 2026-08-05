package com.dlab.common.verification;

import java.time.LocalDate;

/**
 * 본인인증 결과.
 *
 * <p><b>지금은 {@code phone}만 채워진다.</b> 현재 방식(인증번호 발송·확인)이 알아낼 수 있는
 * 것이 전화번호뿐이기 때문이다. 나머지 필드는 <b>PASS(본인확인) 도입을 위한 자리</b>다 —
 * PASS는 이름·생년월일·성별·CI를 함께 돌려준다.
 *
 * <p>지금 record로 만들어 두는 이유는 <b>호출부를 두 번 고치지 않기 위해서</b>다.
 * {@code String}(전화번호)을 그대로 넘기면 PASS를 붙일 때 인증을 쓰는 모든 서비스의
 * 시그니처가 한꺼번에 바뀐다.
 *
 * <p><b>PASS 도입 시 함께 바뀔 것</b> (지금 하지 않는다 — 계약·비용 미확정)
 * <ul>
 *   <li><b>이름을 사용자 입력으로 받지 않는다.</b> 인증 결과의 {@code name}으로 덮어써야
 *       실명이 보장된다 — 입력값을 믿으면 본인확인을 붙인 의미가 없다</li>
 *   <li><b>중복가입 검사를 {@code ci} 기준으로 바꾼다.</b> 전화번호는 바뀌므로 번호만으로는
 *       같은 사람이 다시 가입할 수 있다. CI는 개인마다 고정이라 원래 이 용도다</li>
 *   <li>{@code ci}는 민감정보다. 저장·암호화 기준을 별도로 잡아야 한다</li>
 * </ul>
 *
 * @param phone     인증된 전화번호. 현재 유일하게 채워지는 값
 * @param name      실명. PASS 도입 전에는 {@code null}
 * @param birthDate 생년월일. PASS 도입 전에는 {@code null}
 * @param gender    {@code M}/{@code F}. PASS 도입 전에는 {@code null}
 * @param ci        연계정보(본인확인기관 발급 개인 고유 식별값). PASS 도입 전에는 {@code null}
 */
public record VerifiedIdentity(String phone, String name, LocalDate birthDate,
                               String gender, String ci) {

    /** 현재 방식(인증번호 확인)으로 알아낸 결과 — 전화번호만 있다. */
    public static VerifiedIdentity ofPhoneOnly(String phone) {
        return new VerifiedIdentity(phone, null, null, null, null);
    }

    /**
     * 인증 결과의 실명을 우선하고, 없으면 사용자가 입력한 값을 쓴다.
     *
     * <p>PASS를 붙이는 순간 <b>호출부를 고치지 않아도</b> 실명이 우선되도록 여기에 둔다.
     */
    public String resolveName(String userInput) {
        return name != null && !name.isBlank() ? name : userInput;
    }
}
