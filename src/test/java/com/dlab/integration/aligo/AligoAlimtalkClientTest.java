package com.dlab.integration.aligo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 알림톡 API — <b>문자와 다른 판정</b>을 고정한다.
 *
 * <h2>★★ 성공값이 문자와 반대다</h2>
 * 문자는 {@code result_code == 1}, 알림톡은 <b>{@code code == 0}</b>이다. 같은 벤더인데
 * 다르고, 문자 쪽 판정을 복사하면 <b>성공을 전부 실패로</b> 읽는다. 사람이 기억으로
 * 지킬 수 있는 종류가 아니라 테스트로 묶는다.
 */
class AligoAlimtalkClientTest {

    private static final AligoProperties PROPS =
            new AligoProperties("key", "user", "senderkey", "02-6105-8090", false);

    private record Fixture(AligoAlimtalkClient client, MockRestServiceServer server) {
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new AligoAlimtalkClient(PROPS, builder), server);
    }

    @Test
    @DisplayName("★★ code 0 이 성공이다 — 문자(result_code 1)와 다르다")
    void zeroIsSuccess() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/heartinfo/"))
                // ★ 파라미터 이름이 문자와 다르다 — apikey·userid (밑줄 없음)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apikey=key")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("userid=user")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"정상적으로 호출하였습니다.",
                         "list":{"SMS_CNT":119806,"LMS_CNT":40255,"ALT_CNT":154827}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.client().remainingAlimtalk()).isEqualTo(154827);
        f.server().verify();
    }

    @Test
    @DisplayName("★★ 인증 실패는 HTTP 200 으로 온다 — 코드를 안 보면 「잔여 0」으로 읽는다")
    void failureArrivesAsHttp200() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/heartinfo/"))
                .andRespond(withSuccess("""
                        {"code":-99,"message":"인증되지 않는 서버 IP로 부터의 호출 입니다."}
                        """, MediaType.APPLICATION_JSON));

        // ⚠️ 0을 돌려주면 운영 준비 점검이 「충전하세요」를 띄우는데 실제 원인은 IP다
        assertThatThrownBy(f.client()::remainingAlimtalk)
                .isInstanceOf(AligoException.class)
                .hasMessageContaining("인증되지 않는 서버 IP");
        f.server().verify();
    }

    @Test
    @DisplayName("★ 실패 응답에 숫자 칸이 같이 와도 그 값을 쓰지 않는다")
    void numbersInFailureResponseAreIgnored() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/heartinfo/"))
                .andRespond(withSuccess("""
                        {"code":-99,"message":"오류","list":{"ALT_CNT":0}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(f.client()::remainingAlimtalk)
                .isInstanceOf(AligoException.class);
    }

    @Test
    @DisplayName("숫자가 문자열로 와도 같게 읽는다")
    void stringNumbersAreParsed() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/heartinfo/"))
                .andRespond(withSuccess("""
                        {"code":"0","message":"ok","list":{"ALT_CNT":"1000"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.client().remainingAlimtalk()).isEqualTo(1000);
    }
}
