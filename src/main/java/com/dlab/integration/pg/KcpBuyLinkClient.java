package com.dlab.integration.pg;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * KCP 바이링크 연동.
 *
 * <p>결제 URL 을 만들어 고객에게 문자로 보내는 방식이다. {@code direct_send=Y} 로 두면
 * <b>KCP 가 문자까지 대신 보낸다</b> — 우리 문자 업체가 아직 정해지지 않았어도 결제
 * 링크는 나간다.
 *
 * <h2>★ 우리 서버 최초의 아웃바운드 호출이다</h2>
 * 지금까지 외부를 부르는 코드가 없었다. 실패 처리를 여기서 끝내고 도메인으로 흘리지
 * 않는다 — 결제 실패와 통신 실패는 화면에서 다르게 보여야 한다.
 *
 * <h2>서명</h2>
 * 거래조회·사용중지는 개인키로 서명한다. 대상은 {@code site_cd + "^" + url_reg_id} 이고
 * {@code SHA256withRSA} → Base64 다.
 */
@Slf4j
@Component
public class KcpBuyLinkClient {

    private static final String CREATE_PATH = "/std/url/v1/create";
    /** 가상계좌 발급. 바이링크와 경로가 다르다 */
    private static final String VBANK_PATH = "/gw/hub/v1/payment";
    /** 가상계좌 거래 구분. KCP 고정값이라 바꾸면 거절된다 */
    private static final String VBANK_TXTYPE = "41100000";
    /** 원화. KCP 규격값이다 */
    private static final String CURRENCY_KRW = "410";

    private final KcpProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KcpBuyLinkClient(KcpProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(factory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 결제창 생성은 사용자가 화면 앞에서 기다린다. 무한정 붙들고 있으면 안 된다
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    /**
     * 결제 URL 생성.
     *
     * <p>⚠️ <b>성공 응답이 결제 완료가 아니다.</b> 링크가 만들어졌을 뿐이고, 완료는
     * Webhook 이 확정한다.
     */
    public Created createPayUrl(CreateCommand command) {
        require();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_cd", command.siteCd());
        body.put("kcp_cert_info", certificate());
        body.put("pay_method", command.payMethod());
        body.put("currency", CURRENCY_KRW);
        body.put("ordr_idxx", command.orderNo());
        body.put("good_name", command.goodName());
        body.put("good_mny", String.valueOf(command.amount()));
        body.put("buyr_name", command.buyerName());
        body.put("buyr_tel", command.buyerTel());
        if (command.buyerMail() != null) {
            body.put("buyr_mail", command.buyerMail());
        }
        body.put("reg_id", command.orderNo());
        if (command.expireDate() != null) {
            body.put("url_expire_dt", command.expireDate());
        }
        // Y 면 KCP 가 주문자 연락처로 결제 링크 문자를 보낸다
        body.put("direct_send", command.sendSms() ? "Y" : "N");

        JsonNode response = post(CREATE_PATH, body);
        String code = text(response, "res_cd");
        if (!"0000".equals(code)) {
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED,
                    "결제 링크 생성 실패: %s (%s)".formatted(text(response, "res_msg"), code));
        }
        return new Created(text(response, "pay_url"), text(response, "url_reg_id"));
    }

    /**
     * 가상계좌 발급.
     *
     * <p>⚠️ <b>발급 응답은 결제 완료가 아니다.</b> 계좌번호가 나왔을 뿐이고 입금은 며칠 뒤에
     * 들어오거나 영영 안 들어온다 — 완료는 입금 통보 Webhook 이 확정한다.
     *
     * <p>성공 코드가 바이링크와 다르다({@code V000}). 같은 값으로 판정하면 정상 발급을
     * 실패로 본다.
     */
    public VbankIssued issueVbank(VbankCommand command) {
        require();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site_cd", command.siteCd());
        body.put("kcp_cert_info", certificate());
        body.put("pay_method", "VCNT");
        body.put("amount", String.valueOf(command.amount()));
        body.put("currency", CURRENCY_KRW);
        body.put("ordr_idxx", command.orderNo());
        body.put("good_name", command.goodName());
        body.put("buyr_name", command.buyerName());
        body.put("buyr_tel2", command.buyerTel());
        body.put("va_txtype", VBANK_TXTYPE);
        // ★ amount 와 같아야 한다. 다르면 KCP 가 거절한다
        body.put("va_mny", String.valueOf(command.amount()));
        body.put("va_bankcode", command.bankCode());
        body.put("va_name", command.buyerName());
        // 입금 기한. 지나면 그 계좌로 낼 수 없다
        body.put("va_date", command.expireAt());
        // 0 = 현금영수증 미발급. 발급은 별도 API 라 여기서 처리하지 않는다
        body.put("va_receipt_gubn", "0");

        JsonNode response = post(VBANK_PATH, body);
        String code = text(response, "res_cd");
        if (!"V000".equals(code)) {
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED,
                    "가상계좌 발급 실패: %s (%s)".formatted(text(response, "res_msg"), code));
        }
        return new VbankIssued(
                text(response, "tno"), text(response, "account"),
                text(response, "bankname"), text(response, "bankcode"),
                text(response, "depositor"));
    }

    /**
     * 서명 데이터.
     *
     * <p>거래조회·사용중지에 쓴다. <b>서명 대상 문자열이 요청과 정확히 같아야 한다</b> —
     * 조합 순서를 바꾸면 KCP 가 거절한다.
     */
    public String sign(String siteCd, String urlRegId) {
        require();
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update((siteCd + "^" + urlRegId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PG_REQUEST_FAILED, "서명 생성에 실패했습니다.");
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            String raw = restClient.post()
                    .uri(properties.baseUrlOrDefault() + path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 통신 실패와 결제 거절은 다르다 — 화면 문구가 갈려야 한다
            log.error("KCP 호출 실패: path={}", path, e);
            throw new BusinessException(ErrorCode.PG_UNAVAILABLE,
                    "결제 서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String certificate() {
        return read(properties.certPath());
    }

    private PrivateKey privateKey() throws Exception {
        String pem = read(properties.privateKeyPath())
                .replaceAll("-----(BEGIN|END)[^-]+-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private String read(String path) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PG_NOT_CONFIGURED,
                    "결제 인증서를 읽을 수 없습니다: " + path);
        }
    }

    private void require() {
        if (!properties.configured()) {
            throw new BusinessException(ErrorCode.PG_NOT_CONFIGURED);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asString();
    }

    /**
     * @param goodName 결제창·문자에 보이는 상품명. <b>학생 이름을 넣지 말 것</b> —
     *                 문자가 다른 사람에게 전달될 수 있다
     * @param sendSms  KCP 가 결제 링크 문자를 대신 보낼지
     */
    public record CreateCommand(String siteCd, String orderNo, int amount, String payMethod,
                                String goodName, String buyerName, String buyerTel,
                                String buyerMail, String expireDate, boolean sendSms) {
    }

    public record Created(String payUrl, String urlRegId) {
    }

    /**
     * @param bankCode 입금받을 은행. 은행마다 코드가 다르다(문서 4장 은행코드표)
     * @param expireAt 입금 기한 {@code yyyyMMddHHmmss}
     */
    public record VbankCommand(String siteCd, String orderNo, int amount, String goodName,
                               String buyerName, String buyerTel, String bankCode,
                               String expireAt) {
    }

    public record VbankIssued(String tno, String account, String bankName,
                              String bankCode, String depositor) {
    }
}
