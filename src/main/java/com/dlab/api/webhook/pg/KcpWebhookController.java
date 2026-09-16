package com.dlab.api.webhook.pg;

import com.dlab.domain.payment.service.PaymentRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    @PostMapping(value = "/buylink", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> buyLink(HttpServletRequest request) {
        Map<String, String> data = read(request);
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

    /**
     * 가상계좌 입금 통보.
     *
     * <p>바이링크와 파라미터 이름이 다르다 — 주문번호가 {@code order_no}, 금액이
     * {@code ipgm_mnyx}, 입금자가 {@code ipgm_name} 이다. 같은 핸들러로 받으면 값이
     * 전부 비어 수납이 안 잡힌다.
     *
     * <p>⚠️ <b>{@code op_cd=13} 은 입금 취소</b>다(기관 망 취소). 이 경우 수납을 잡으면
     * 들어오지 않은 돈이 기록된다 — 기록만 남기고 사람이 확인한다.
     */
    @PostMapping(value = "/vbank", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> vbankDeposit(HttpServletRequest request) {
        Map<String, String> data = read(request);
        String orderNo = data.get("order_no");
        String tno = data.get("tno");
        String opCd = data.get("op_cd");

        if ("13".equals(opCd)) {
            // 입금 취소. 자동으로 수납을 되돌리지 않는다 — 이미 환불했는지 사람이 확인해야 한다
            log.error("가상계좌 입금 취소 통보 — 확인 필요: orderNo={}, tno={}", orderNo, tno);
            return Map.of("res_cd", OK, "res_msg", "정상처리");
        }

        try {
            boolean first = paymentRequestService.confirmVbankDeposit(orderNo, tno,
                    amount(data.get("ipgm_mnyx")), data.get("ipgm_name"));
            if (!first) {
                log.info("가상계좌 재전송 수신 — 이미 처리됨: orderNo={}, tno={}", orderNo, tno);
            }
            return Map.of("res_cd", OK, "res_msg", "정상처리");
        } catch (Exception e) {
            log.error("가상계좌 입금 처리 실패 — 재전송 대기: orderNo={}, tno={}", orderNo, tno, e);
            return Map.of("res_cd", "9999", "res_msg", "처리 실패");
        }
    }

    /**
     * 전문을 읽는다.
     *
     * <p>★ <b>form 과 JSON 이 둘 다 온다.</b> 가이드의 예시가 한쪽은 {@code &} 로 이어붙인
     * 폼이고 다른 쪽은 JSON 이라, 어느 형식으로 올지 우리가 고를 수 없다.
     *
     * <p>⚠️ {@code @RequestParam Map} 과 {@code @RequestBody Map} 을 한 메서드에 함께 두면
     * <b>폼 요청에서 500 이 난다</b> — 본문이 이미 파라미터로 소비돼 JSON 변환기가 읽을
     * 것이 없다. 실제로 그렇게 짰다가 테스트에서 잡혔다. 그래서 요청을 직접 읽는다.
     */
    private Map<String, String> read(HttpServletRequest request) {
        Map<String, String> data = new java.util.LinkedHashMap<>();
        // 폼이면 서블릿이 이미 파싱해 둔다
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                data.put(k, v[0]);
            }
        });
        if (!data.isEmpty()) {
            return data;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("json")) {
            return data;
        }
        try {
            String body = request.getReader().lines()
                    .collect(java.util.stream.Collectors.joining());
            if (body.isBlank()) {
                return data;
            }
            tools.jackson.databind.JsonNode node = MAPPER.readTree(body);
            node.propertyStream().forEach(e ->
                    data.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
        } catch (Exception e) {
            // 본문을 못 읽으면 값이 비어 아래 대조에서 걸린다 — 여기서 터뜨리지 않는다
            log.error("KCP 전문을 읽지 못했다", e);
        }
        return data;
    }

    private static final tools.jackson.databind.ObjectMapper MAPPER =
            new tools.jackson.databind.ObjectMapper();

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
