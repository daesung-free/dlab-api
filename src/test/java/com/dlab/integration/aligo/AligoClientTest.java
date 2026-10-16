package com.dlab.integration.aligo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * 알리고 문자 발송 — <b>응답 해석</b>을 고정한다.
 *
 * <h2>★ 왜 이것만은 테스트로 못박아야 하는가</h2>
 * 알리고는 <b>실패해도 HTTP 200</b> 을 준다. 실패는 본문 {@code result_code} 에만 있고,
 * 사유는 {@code message} 문자열에만 있다(문서에 코드 목록이 없고 {@code -99} 하나에
 * 필수값 누락·포인트 부족·인증키 오류가 전부 몰려 있다).
 *
 * <p>그래서 판정을 한 글자 잘못 쓰면 <b>아무에게도 문자가 안 가는데 발송 이력은 성공으로
 * 남는다.</b> 이 프로젝트에서 반복해서 당한 부류이고(목업 발송기·급식 폴백),
 * <b>화면·로그 어디에도 증거가 남지 않는 종류의 실패</b>라 더 위험하다.
 *
 * <p>실제 알리고로는 확인할 수 없다 — 호출 IP 등록이 끝나야 응답이 오고, 잔액 부족 같은
 * 실패 응답은 일부러 만들어 낼 수도 없다. 그래서 서버를 바꿔 끼워 검증한다.
 */
class AligoClientTest {

    private static final AligoProperties LIVE = new AligoProperties(
            "key", "user", "senderkey", "02-6105-8090", false);
    private static final AligoProperties TEST_MODE = new AligoProperties(
            "key", "user", "senderkey", "02-6105-8090", true);

    private record Fixture(AligoClient client, MockRestServiceServer server) {
    }

    private Fixture fixture(AligoProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new AligoClient(properties, builder), server);
    }

    @Test
    @DisplayName("★★ result_code 가 1 이 아니면 실패다 — HTTP 200 이어도 던진다")
    void nonSuccessResultCodeThrowsEvenOnHttp200() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andRespond(withSuccess("""
                        {"result_code":-101,"message":"인증오류.-IP"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.client().send("01012345678", null, "본문",
                AligoClient.MessageType.SMS))
                .isInstanceOf(AligoException.class)
                // ★ 사유를 싣는다 — 코드만 남기면 원인을 알 수 없다
                .hasMessageContaining("인증오류")
                .hasMessageContaining("-101");
    }

    @Test
    @DisplayName("잔액 부족도 같은 방식으로 드러난다 — 선불이라 0 이면 전부 실패한다")
    void insufficientBalanceIsReportedAsFailure() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andRespond(withSuccess("""
                        {"result_code":-99,"message":"보유 포인트가 부족합니다."}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> f.client().send("01012345678", null, "본문",
                AligoClient.MessageType.SMS))
                .isInstanceOf(AligoException.class)
                .hasMessageContaining("포인트");
    }

    @Test
    @DisplayName("result_code 1 이면 성공 — 성공 응답에서는 던지지 않는다")
    void successResultCodePasses() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andRespond(withSuccess("""
                        {"result_code":1,"message":"success","msg_id":12345,
                         "success_cnt":1,"error_cnt":0,"msg_type":"SMS"}
                        """, MediaType.APPLICATION_JSON));

        assertThatCode(() -> f.client().send("010-1234-5678", null, "본문",
                AligoClient.MessageType.SMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("응답 숫자가 문자열로 와도 같게 판정한다 — 벤더가 칸마다 다르게 준다")
    void resultCodeAsStringIsAccepted() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andRespond(withSuccess("""
                        {"result_code":"1","message":"success"}
                        """, MediaType.APPLICATION_JSON));

        assertThatCode(() -> f.client().send("01012345678", null, "본문",
                AligoClient.MessageType.SMS)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 테스트 모드면 testmode_yn=Y 를 싣는다 — 과금도 실발송도 없다")
    void testModeSendsFlag() {
        var f = fixture(TEST_MODE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("testmode_yn=Y")))
                .andRespond(withSuccess("{\"result_code\":1}", MediaType.APPLICATION_JSON));

        f.client().send("01012345678", null, "본문", AligoClient.MessageType.SMS);
        f.server().verify();
    }

    @Test
    @DisplayName("실제 발송 모드에서는 testmode_yn 을 넣지 않는다")
    void liveModeOmitsFlag() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("testmode_yn"))))
                .andRespond(withSuccess("{\"result_code\":1}", MediaType.APPLICATION_JSON));

        f.client().send("01012345678", null, "본문", AligoClient.MessageType.SMS);
        f.server().verify();
    }

    @Test
    @DisplayName("★ 번호는 숫자만 보낸다 — 하이픈이 섞여 들어와도 호출은 같아야 한다")
    void phoneNumbersAreSentAsDigits() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("receiver=01012345678"),
                        org.hamcrest.Matchers.containsString("sender=0261058090"))))
                .andRespond(withSuccess("{\"result_code\":1}", MediaType.APPLICATION_JSON));

        f.client().send("010-1234-5678", null, "본문", AligoClient.MessageType.SMS);
        f.server().verify();
    }

    @Test
    @DisplayName("잔여 건수를 읽는다 — 운영 준비 점검이 이 값으로 BLOCKER 를 판정한다")
    void remainIsParsed() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/remain/"))
                .andRespond(withSuccess("""
                        {"result_code":1,"message":"success",
                         "SMS_CNT":1200,"LMS_CNT":400,"MMS_CNT":0}
                        """, MediaType.APPLICATION_JSON));

        AligoClient.Remain remain = f.client().remain();
        assertThat(remain.sms()).isEqualTo(1200);
        assertThat(remain.lms()).isEqualTo(400);
        assertThat(remain.mms()).isZero();
    }

    @Test
    @DisplayName("잔여 조회가 실패하면 0 건으로 읽지 않고 던진다 — 0 과 「모른다」는 다르다")
    void remainFailureThrowsInsteadOfReturningZero() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/remain/"))
                .andRespond(withSuccess("""
                        {"result_code":-101,"message":"인증오류.-IP","SMS_CNT":0,"LMS_CNT":0}
                        """, MediaType.APPLICATION_JSON));

        // ★ 실패 응답에도 SMS_CNT 가 0 으로 들어 있다. 코드를 안 보면 「잔액 0」으로 읽는다
        assertThatThrownBy(() -> f.client().remain()).isInstanceOf(AligoException.class);
    }

    @Test
    @DisplayName("★ 90바이트를 넘으면 LMS 다 — 벤더 자동 전환에 맡기지 않는다(단가가 말없이 바뀐다)")
    void messageTypeIsDecidedByEucKrLength() {
        // EUC-KR 에서 한글은 2바이트다 — 45자까지 SMS
        assertThat(AligoClient.MessageType.of("가".repeat(45)))
                .isEqualTo(AligoClient.MessageType.SMS);
        assertThat(AligoClient.MessageType.of("가".repeat(46)))
                .isEqualTo(AligoClient.MessageType.LMS);
        assertThat(AligoClient.MessageType.of("a".repeat(90)))
                .isEqualTo(AligoClient.MessageType.SMS);
        assertThat(AligoClient.MessageType.of("a".repeat(91)))
                .isEqualTo(AligoClient.MessageType.LMS);
    }

    @Test
    @DisplayName("SMS 에는 제목을 싣지 않는다 — 제목은 LMS 에만 적용된다")
    void smsOmitsTitle() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("title"))))
                .andRespond(withSuccess("{\"result_code\":1}", MediaType.APPLICATION_JSON));

        f.client().send("01012345678", "제목", "본문", AligoClient.MessageType.SMS);
        f.server().verify();
    }

    @Test
    @DisplayName("LMS 제목은 44바이트에서 자른다 — 넘기면 호출 자체가 거절된다")
    void lmsTitleIsClamped() {
        var f = fixture(LIVE);
        f.server().expect(requestTo("https://apis.aligo.in/send/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("title=")))
                .andRespond(withSuccess("{\"result_code\":1}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> f.client().send("01012345678", "제".repeat(50), "본문",
                AligoClient.MessageType.LMS)).doesNotThrowAnyException();
        f.server().verify();
    }
}
