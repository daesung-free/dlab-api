package com.dlab.api.webhook;

import com.dlab.domain.payment.service.PaymentRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * KCP 결제 Webhook.
 *
 * <p>★ <b>서비스가 아니라 HTTP 경로를 검증한다.</b> 이 구간의 실수는 전부
 * "파라미터 이름을 하나 틀렸다" 류인데, 서비스만 직접 부르는 테스트로는 절대 안 잡힌다 —
 * 값이 비어 들어와도 컴파일이 되고, 운영에서는 <b>수납이 조용히 안 잡히는</b> 것으로만 보인다.
 *
 * <p>지키려는 것 — <b>바이링크·가상계좌의 다른 파라미터 이름</b>,
 * <b>KCP 가 인식하는 응답 형식</b>, <b>입금 취소(op_cd=13)를 수납으로 잡지 않을 것</b>,
 * <b>인증 없이 열려 있을 것</b>(KCP 는 우리 토큰을 모른다).
 */
@SpringBootTest
@Transactional
class KcpWebhookTest {

    @Autowired WebApplicationContext context;

    @MockitoBean PaymentRequestService paymentRequestService;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("★ 바이링크 승인 통보 — 토큰 없이 들어오고 result=0000 을 돌려준다")
    void buyLinkApproved() throws Exception {
        when(paymentRequestService.confirm(anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);

        mvc.perform(post("/api/v1/webhook/kcp/buylink")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("res_cd", "0000")
                        .param("tx_cd", "NXA3")
                        .param("ordr_idxx", "B10-1700000000000")
                        .param("tno", "24822000013223")
                        .param("amount", "750000")
                        .param("card_name", "현대카드")
                        .param("card_no", "523930******0000"))
                .andExpect(status().isOk())
                // ★ KCP 는 본문에서 result 를 찾는다. 없거나 다르면 10번까지 재전송한다
                .andExpect(jsonPath("$.result").value("0000"));

        verify(paymentRequestService).confirm(eq("B10-1700000000000"), eq("24822000013223"),
                eq(750000), anyString());
    }

    @Test
    @DisplayName("실패 통보는 수납으로 잡지 않는다 — 다만 정상 수신으로 답한다")
    void buyLinkFailureIsNotSettled() throws Exception {
        mvc.perform(post("/api/v1/webhook/kcp/buylink")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("res_cd", "9999")
                        .param("res_msg", "한도초과")
                        .param("ordr_idxx", "B10-1")
                        .param("amount", "750000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("0000"));

        // 재전송받을 이유가 없다 — 실패는 이미 확정된 결과다
        verify(paymentRequestService, never()).confirm(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("★ 처리 중 오류면 실패로 답한다 — KCP 가 다시 보내줘야 유실되지 않는다")
    void buyLinkErrorAsksForRetry() throws Exception {
        when(paymentRequestService.confirm(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("DB 오류"));

        mvc.perform(post("/api/v1/webhook/kcp/buylink")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("res_cd", "0000")
                        .param("ordr_idxx", "B10-1")
                        .param("tno", "T1")
                        .param("amount", "750000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("9999"));
    }

    @Test
    @DisplayName("★★ 가상계좌는 파라미터 이름이 다르다 — order_no · ipgm_mnyx · ipgm_name")
    void vbankUsesDifferentParamNames() throws Exception {
        when(paymentRequestService.confirmVbankDeposit(anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);

        mvc.perform(post("/api/v1/webhook/kcp/vbank")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("site_cd", "AO8M2")
                        .param("tno", "20250521435727")
                        .param("order_no", "B11-1700000000000")
                        .param("ipgm_mnyx", "750000")
                        .param("ipgm_name", "김할머니")
                        .param("op_cd", "50"))
                .andExpect(status().isOk())
                // ★ 가상계좌는 응답 키도 다르다(res_cd). result 로 답하면 재전송된다
                .andExpect(jsonPath("$.res_cd").value("0000"));

        // 이름을 하나만 틀려도 값이 비어 수납이 조용히 안 잡힌다
        verify(paymentRequestService).confirmVbankDeposit(eq("B11-1700000000000"),
                eq("20250521435727"), eq(750000), eq("김할머니"));
    }

    @Test
    @DisplayName("★ op_cd=13 은 입금 취소다 — 들어오지 않은 돈을 수납으로 잡지 않는다")
    void vbankCancelIsNotSettled() throws Exception {
        mvc.perform(post("/api/v1/webhook/kcp/vbank")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("tno", "T2")
                        .param("order_no", "B11-1")
                        .param("ipgm_mnyx", "750000")
                        .param("op_cd", "13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.res_cd").value("0000"));

        verify(paymentRequestService, never())
                .confirmVbankDeposit(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("JSON 으로 와도 받는다 — 전문이 form 으로도 JSON 으로도 온다")
    void acceptsJsonBody() throws Exception {
        when(paymentRequestService.confirm(anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);

        mvc.perform(post("/api/v1/webhook/kcp/buylink")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"res_cd":"0000","ordr_idxx":"B12-1","tno":"T3","amount":"55000"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("0000"));

        verify(paymentRequestService).confirm(eq("B12-1"), eq("T3"), eq(55000), anyString());
    }
}
