package com.dlab.common.verification;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증번호 고정 설정 — <b>지정한 번호에만</b> 적용된다.
 *
 * <h2>왜 필요한가</h2>
 * 문자 발송이 아직 연동 전이라 인증번호가 <b>서버 로그로만</b> 나간다. 테스트할 때마다
 * 로그를 열어야 하고, <b>스토어 심사 때는 그마저도 안 된다</b> — 심사자가 직접 가입해
 * 보는데 인증번호를 받을 방법이 없어 그 자리에서 막힌다.
 *
 * <h2>★ 인증을 건너뛰는 것이 아니다</h2>
 * 고정값을 <b>실제 인증번호로 저장</b>한다. 그래서 확인 단계·시도 횟수 제한·토큰 발급이
 * <b>전부 그대로 돈다</b> — 번호가 예측 가능해지는 것뿐이다. 검증을 우회하는 분기를
 * 만들면 그 분기가 운영에서 켜졌을 때 <b>인증 자체가 사라진다.</b>
 *
 * <h2>★ 번호 목록이 없으면 아무 일도 일어나지 않는다</h2>
 * 전역 스위치를 두지 않았다. {@code enabled=true} 하나로 켜지는 구조면 <b>운영에서 실수로
 * 켰을 때 모든 번호가 뚫린다.</b> 여기 적힌 번호에만 적용되므로, 심사용 번호 하나만
 * 넣어 두면 그 번호 외에는 평소와 같다.
 *
 * @param code   고정 인증번호. 6자리 숫자여야 한다(확인 단계의 형식 검증을 그대로 통과해야 한다)
 * @param phones 적용할 번호 목록. 하이픈이 있든 없든 <b>숫자만 비교</b>한다
 */
@ConfigurationProperties(prefix = "verification.fixed-code")
public record VerificationProperties(String code, List<String> phones) {

    public boolean appliesTo(String phone) {
        if (code == null || code.isBlank() || phones == null || phones.isEmpty()) {
            return false;
        }
        String target = digitsOf(phone);
        return !target.isEmpty() && phones.stream().anyMatch(p -> digitsOf(p).equals(target));
    }

    /** 설정된 번호가 하나라도 있는가 — 기동 경고를 띄울지 판단한다. */
    public boolean configured() {
        return code != null && !code.isBlank() && phones != null && !phones.isEmpty();
    }

    public int phoneCount() {
        return phones == null ? 0 : phones.size();
    }

    /** 하이픈·공백을 흘려보낸다 — 설정과 요청의 표기가 달라도 같은 번호로 본다. */
    private static String digitsOf(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
