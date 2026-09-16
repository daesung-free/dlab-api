package com.dlab.api.webhook.pg;

import com.dlab.domain.payment.service.PaymentRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * KCP 결제 완료 수신.
 *
 * <h2>★ 이 경로가 수납을 확정한다</h2>
 * 결제 URL 생성 응답은 "링크가 만들어졌다" 일 뿐이다. KCP 가이드가 명시한다 —
 * <i>"URL 생성 응답만으로 주문처리 하지 말고 반드시 Webhook 데이터 확인 후 완료 처리"</i>.
 *
 * <h2>★★ 응답 형식이 우리 규약과 다르다</h2>
 * {@code ApiResponse} 로 감싸지 않는다. KCP 는 본문에서 <b>{@code result=0000}</b> 을 찾고,
 * 없거나 다르면 <b>전송 실패로 보고 최대 10번 재전송</b>한다. 감싸면 영원히 재전송된다 —
 * 키오스크 구획(§7)과 같은 이유의 예외다.
 *
 * <h2>인증이 없다</h2>
 * KCP 는 우리 토큰을 모른다. 대신 <b>주문번호와 승인 금액을 대조</b>해서 위조를 막는다 —
 * 금액이 우리 청구와 다르면 수납을 잡지 않는다. ⚠️ 이 경로는 인터넷에 열려 있으므로
 * <b>여기서 하는 일을 늘리지 말 것</b>.
 */
@Slf4j
@Tag(name = "Webhook · KCP 결제")
@RestController
@RequestMapping("/api/v1/webhook/kcp")
@RequiredArgsConstructor
public class KcpWebhookController {

    /** KCP 가 성공으로 인식하는 유일한 값. */
    private static final String OK = "0000";

    private final PaymentRequestService paymentRequestService;

    /**
     * 바이링크 결제 완료.
     *
     * <p>전문이 form 으로도 JSON 으로도 올 수 있어 {@code Map} 으로 받는다.
     *
     * <p><b>실패해도 200 을 돌려주는 경우가 있다</b> — 우리 쪽 문제로 계속 재전송받으면
     * 같은 건이 10번 쌓이고 로그가 묻힌다. 다만 <b>아직 처리하지 못한 것</b>(주문번호를
     * 못 찾는 등)은 실패로 돌려줘야 KCP 가 다시 보낸다.
     */
    @PostMapping(value = "/buylink",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> buyLink(@RequestParam(required = false) Map<String, String> form,
                                       @RequestBody(required = false) Map<String, Object> json) {
        Map<String, String> data = merge(form, json);
        String resCd = data.get("res_cd");
        String orderNo = data.get("ordr_idxx");
        String tno = data.get("tno");

        if (!OK.equals(resCd)) {
            // 실패 통보도 온다. 기록만 남기고 정상 수신으로 답한다 — 재전송받을 이유가 없다
            log.warn("KCP 결제 실패 통보: orderNo={}, res_cd={}, res_msg={}",
                    orderNo, resCd, data.get("res_msg"));
            return Map.of("result", OK);
        }

        try {
            boolean first = paymentRequestService.confirm(orderNo, tno,
                    amount(data.get("amount")), detail(data));
            if (!first) {
                log.info("KCP 재전송 수신 — 이미 처리됨: orderNo={}, tno={}", orderNo, tno);
            }
            return Map.of("result", OK);
        } catch (Exception e) {
            // ★ 여기서만 실패로 답한다. KCP 가 다시 보내줘야 유실되지 않는다
            log.error("KCP 결제 처리 실패 — 재전송 대기: orderNo={}, tno={}", orderNo, tno, e);
            return Map.of("result", "9999", "message", "처리 실패");
        }
    }

    private Map<String, String> merge(Map<String, String> form, Map<String, Object> json) {
        Map<String, String> data = new java.util.LinkedHashMap<>();
        if (form != null) {
            data.putAll(form);
        }
        if (json != null) {
            json.forEach((k, v) -> data.put(k, v == null ? null : String.valueOf(v)));
        }
        return data;
    }

    private int amount(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;      // 금액 대조에서 걸린다
        }
    }

    /** 영수증에 쓸 것만 남긴다. 카드번호 전체·주문자 정보를 그대로 적재하지 않는다. */
    private String detail(Map<String, String> data) {
        String cardName = data.getOrDefault("card_name", "");
        String cardNo = data.getOrDefault("card_no", "");
        String quota = data.getOrDefault("quota", "");
        return "%s %s %s".formatted(cardName, cardNo, quota).trim();
    }
}
