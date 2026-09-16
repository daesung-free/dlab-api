package com.dlab.integration.pg;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KCP 실통신 확인 (테스트 가맹점).
 *
 * <h2>왜 필요한가</h2>
 * 나머지 결제 테스트는 {@link KcpBuyLinkClient} 를 목으로 대체한다. 그래서 <b>전문 포맷·
 * 인증서 로딩·서명이 맞는지는 한 번도 확인되지 않는다</b> — 필드 이름 하나가 틀려도
 * 우리 테스트는 전부 통과하고, 운영에서 처음 터진다.
 *
 * <p>여기서는 KCP <b>테스트 서버</b>(stg-spl)에 문서의 테스트 사이트코드({@code T0000})로
 * 실제 요청을 보낸다. 돈이 오가지 않고 실제 가맹점 정보도 쓰지 않는다.
 *
 * <h2>파일이 없으면 건너뛴다</h2>
 * 인증서는 {@code md/}(gitignore) 에 있어 CI 에는 없다. 없으면 이 테스트는 조용히 빠진다 —
 * <b>CI 를 외부 서버 상태에 묶지 않기 위해서다.</b> KCP 점검 중에 우리 배포가 막히면 안 된다.
 */
class KcpLiveSmokeTest {

    private static final String CERT = "md/결제/인증서_테스트/splCert.pem";
    private static final String KEY = "md/결제/인증서_테스트/splPrikeyPKCS8.pem";
    /** 문서에 공개된 테스트 키 비밀번호 */
    private static final String KEY_PASSWORD = "changeit";
    private static final String TEST_SITE_CD = "T0000";
    /** ★ reg_id 는 상점관리자 계정이다. 주문번호를 넣으면 S005 로 거절된다 */
    private static final String TEST_REG_ID = "testkcp_4321";

    static boolean certificateExists() {
        return Files.exists(Path.of(CERT)) && Files.exists(Path.of(KEY));
    }

    private KcpBuyLinkClient client() {
        return new KcpBuyLinkClient(new KcpProperties(
                "https://stg-spl.kcp.co.kr", CERT, KEY, KEY_PASSWORD));
    }

    @Test
    @EnabledIf("certificateExists")
    @DisplayName("★ 결제 URL 생성 — 전문 포맷과 인증서가 실제로 받아들여진다")
    void createsPayUrl() {
        var created = client().createPayUrl(new KcpBuyLinkClient.CreateCommand(
                TEST_SITE_CD, TEST_REG_ID, "TEST-" + System.currentTimeMillis(), 1000, "CARD",
                "2026년 9월 교습비", "홍길동", "01012341234", null,
                java.time.LocalDate.now().plusDays(7)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")),
                // ★ 문자를 보내지 않는다. 테스트 번호로 실제 문자가 나가면 안 된다
                false));

        System.out.println("결제 URL: " + created.payUrl());
        assertThat(created.payUrl()).startsWith("http");
        assertThat(created.urlRegId()).isNotBlank();
    }

    @Test
    @EnabledIf("certificateExists")
    @DisplayName("★ 서명 — 암호화된 개인키를 풀어 SHA256withRSA 로 서명한다")
    void signsWithEncryptedKey() {
        String signature = client().sign(TEST_SITE_CD, "url-reg-sample");

        // 실패하면 예외가 나므로, 값이 나왔다는 것 자체가 복호화·서명이 됐다는 뜻이다
        assertThat(signature).isNotBlank();
        assertThat(java.util.Base64.getDecoder().decode(signature)).hasSizeGreaterThan(100);
    }

    @Test
    @EnabledIf("certificateExists")
    @DisplayName("가상계좌 발급 — 성공 코드가 V000 이라 바이링크와 판정이 다르다")
    void issuesVbank() {
        var issued = client().issueVbank(new KcpBuyLinkClient.VbankCommand(
                TEST_SITE_CD, "TESTV-" + System.currentTimeMillis(), 1000,
                "2026년 9월 교습비", "홍길동", "01012341234", "BK26",
                java.time.LocalDateTime.now().plusDays(7)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))));

        System.out.println("가상계좌: " + issued.bankName() + " " + issued.account()
                + " / 예금주 " + issued.depositor() + " / tno " + issued.tno());
        assertThat(issued.account()).isNotBlank();
        assertThat(issued.tno()).isNotBlank();
    }
}
