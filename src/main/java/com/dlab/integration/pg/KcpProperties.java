package com.dlab.integration.pg;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KCP 연동 설정.
 *
 * <p>★ <b>사이트코드는 여기 없다.</b> 사업자 × 채널(× 지점)로 여러 개라 한 칸에 담기지
 * 않는다 — {@code pg_site} 테이블에 두고 관리자 화면에서 넣는다.
 *
 * <p>★ <b>인증서·개인키는 경로로 받는다.</b> PEM 은 여러 줄이라 환경변수 한 줄에 맞지 않고,
 * 값을 통째로 넣으면 로그·오류 메시지에 딸려 나갈 위험이 커진다. 서버에 파일로 두고
 * ({@code chmod 600}) 경로만 설정한다. ⚠️ 레포에 넣지 말 것.
 *
 * <p>비어 있어도 앱은 뜬다 — 자격증명이 없는 로컬·CI 에서 결제와 무관한 작업까지
 * 멈추면 안 된다. 실제로 호출할 때 실패한다.
 *
 * @param baseUrl            테스트 {@code https://stg-spl.kcp.co.kr}, 운영 {@code https://spl.kcp.co.kr}
 * @param privateKeyPassword ★ <b>KCP 개인키는 암호화되어 있다.</b> 발급 시 정한 비밀번호가
 *                           없으면 서명을 만들 수 없다(문서의 테스트 키는 {@code changeit}).
 *                           비워두면 암호화되지 않은 키로 간주하고 그대로 읽는다
 */
@ConfigurationProperties(prefix = "kcp")
public record KcpProperties(String baseUrl, String certPath, String privateKeyPath,
                            String privateKeyPassword) {

    public boolean configured() {
        return notBlank(baseUrl) && notBlank(certPath) && notBlank(privateKeyPath);
    }

    public String baseUrlOrDefault() {
        return notBlank(baseUrl) ? baseUrl : "https://stg-spl.kcp.co.kr";
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
