package com.dlab.integration.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 성적표 수신 클라이언트.
 *
 * <p>개발서버가 아직 열리지 않아 <b>가짜 서버로 계약만 고정</b>한다. 확인하는 것은 셋이다.
 * <ol>
 *   <li>★ <b>서명한 문자열과 실제 요청 URL 이 같은가</b> — 어긋나면 401 만 보게 된다</li>
 *   <li>★ <b>404 가 예외가 아니라 {@code null}</b> 인가 — 한 건이 없다고 배치가 멈추면
 *       뒤의 학생이 전부 안 받아진다</li>
 *   <li>헤더 셋이 실리는가</li>
 * </ol>
 */
class PremiumReportClientTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.ofEpochSecond(1755590400L), ZoneOffset.UTC);
    private static final String BASE = "https://premium.test";

    private final PremiumProperties properties =
            new PremiumProperties(BASE, "dsdo-test-key", "test-secret", null, null);

    @Test
    @DisplayName("★★ 서명한 문자열과 실제 요청 URL 이 같다 — 빌더가 끼면 여기서 어긋난다")
    void signsExactlyWhatItRequests() {
        String expectedPath =
                "/api/students/report?examdt=20260417&schoolId=99700&grade=3&classNumber=11&bunho=1101";
        String expectedSignature = PremiumSignature.hmacHex("test-secret",
                "1755590400:GET:" + expectedPath);

        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .requestTo(BASE + expectedPath))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("X-API-KEY", "dsdo-test-key"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("X-TIMESTAMP", "1755590400"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("X-SIGNATURE", expectedSignature))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("%PDF-1.4".getBytes(), MediaType.APPLICATION_PDF));

        byte[] pdf = new PremiumReportClient(properties, FIXED, builder)
                .downloadReport("20260417", "99700", 3, 11, "1101");

        assertThat(pdf).isNotNull();
        assertThat(new String(pdf)).startsWith("%PDF");
        server.verify();
    }

    @Test
    @DisplayName("★★ 성적표가 없으면 null 이다 — 예외면 그 회차 뒤 학생이 전부 안 받아진다")
    void missingReportIsNullNotException() {
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withResourceNotFound()
                        .body("{\"status\":\"FAIL_GRADE_03\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(new PremiumReportClient(properties, FIXED, builder)
                .downloadReport("20260417", "99700", 3, 1, "1001")).isNull();
    }

    @Test
    @DisplayName("★ 연결 확인은 404 여도 통과다 — 인증이 맞는지만 보는 것이라")
    void pingPassesOnNotFound() {
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withResourceNotFound());

        assertThat(new PremiumReportClient(properties, FIXED, builder)
                .ping("20260417", "99700", 3, 1, "1001")).isTrue();
    }

    @Test
    @DisplayName("★ 인증 실패(401)는 연결 확인이 거짓이다 — 키·시계·서명 중 하나가 틀렸다")
    void pingFailsOnUnauthorized() {
        var builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withUnauthorizedRequest()
                        .body("{\"status\":\"FAIL_AUTH_03\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(new PremiumReportClient(properties, FIXED, builder)
                .ping("20260417", "99700", 3, 1, "1001")).isFalse();
    }
}
