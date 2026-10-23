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

    @Test
    @DisplayName("★★ 발송은 /akv10/alimtalk/send/ 로 가고 수신자 파라미터에 _1 이 붙는다")
    void sendUsesAlimtalkEndpoint() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/alimtalk/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apikey=key")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("userid=user")))
                // ★ 발신프로필 키 — 문자에는 없는 값이고 빠지면 인증 오류가 난다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("senderkey=senderkey")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tpl_code=UM_8230")))
                // ★ 하이픈을 떼고 숫자만 보낸다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sender=0261058090")))
                // ★★ 꼬리 _1 을 떼면 「필수값 누락」이 난다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("receiver_1=01012345678")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("subject_1=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("message_1=")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"성공적으로 전송요청 하였습니다.",
                         "info":{"type":"AT","mid":12345,"scnt":"1","fcnt":"0"}}
                        """, MediaType.APPLICATION_JSON));

        f.client().send("010-1234-5678", "UM_8230", "미등원 안내", "홍길동 학생이 등원하지 않았습니다.");
        f.server().verify();
    }

    @Test
    @DisplayName("★★ 실패는 HTTP 200 으로 온다 — 던지지 않으면 안 간 알림이 성공으로 남는다")
    void sendFailureIsThrown() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/alimtalk/send/"))
                .andRespond(withSuccess("""
                        {"code":-105,"message":"템플릿 정보가 존재하지 않습니다."}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.client().send("01012345678", "UM_9999", "제목", "본문"))
                .isInstanceOf(AligoException.class)
                .hasMessageContaining("템플릿 정보가 존재하지 않습니다");
        f.server().verify();
    }

    @Test
    @DisplayName("★ 테스트 모드면 testmode_yn=Y 를 함께 보낸다 — 문자와 같은 규약이다")
    void testModeSendsFlag() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new AligoAlimtalkClient(
                new AligoProperties("key", "user", "senderkey", "02-6105-8090", true), builder);

        server.expect(requestTo("https://kakaoapi.aligo.in/akv10/alimtalk/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("testmode_yn=Y")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","info":{"mid":1,"scnt":"1","fcnt":"0"}}
                        """, MediaType.APPLICATION_JSON));

        client.send("01012345678", "UM_8230", "제목", "본문");
        server.verify();
    }

    @Test
    @DisplayName("★ 제목이 비어 있으면 기본값으로 채운다 — subject_1 이 필수라 호출이 거절된다")
    void blankSubjectIsFilled() {
        var f = fixture();
        f.server().expect(requestTo("https://kakaoapi.aligo.in/akv10/alimtalk/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("subject_1=%EC%95%8C%EB%A6%BC")))
                .andRespond(withSuccess("""
                        {"code":0,"message":"ok","info":{"mid":1,"scnt":"1","fcnt":"0"}}
                        """, MediaType.APPLICATION_JSON));

        f.client().send("01012345678", "UM_8230", "  ", "본문");
        f.server().verify();
    }
}
