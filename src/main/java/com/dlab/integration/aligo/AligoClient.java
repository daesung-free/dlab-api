package com.dlab.integration.aligo;

import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 알리고 문자 API 호출부. 발송·잔액 조회를 한 곳에 모은다.
 *
 * <h2>★★ 성공 판정은 HTTP 상태가 아니라 {@code result_code == 1} 이다</h2>
 * 실패해도 <b>HTTP 200</b> 이 온다. 상태코드만 보면 잔액이 0이거나 발신번호가 등록되지
 * 않아도 "보냈다"로 기록되고, <b>아무에게도 문자가 가지 않는데 화면은 정상</b>이 된다 —
 * 이 프로젝트에서 반복해서 당한 부류다(목업 발송기와 같은 모양).
 *
 * <p>⚠️ <b>알림톡 API 는 성공이 {@code 0} 이다</b>(문자는 {@code 1}). 같은 벤더인데 다르다 —
 * 알림톡 구현체를 붙일 때 이 클래스의 판정을 그대로 복사하지 말 것.
 *
 * <h2>★ {@code msg_type} 을 명시한다</h2>
 * 비워 두면 알리고가 <b>90바이트를 넘는 순간 LMS 로 자동 전환</b>한다. 단가가 달라지고
 * 제목 규칙도 달라져서, 문구를 한 글자 늘린 것이 과금 변경이 되는 상태가 된다.
 *
 * <h2>★ 이모지를 넣지 말 것</h2>
 * 통신사 발송 시스템이 EUC-KR 이라 변환되지 않는 문자는 <b>물음표로 바뀌어</b> 나간다.
 * 문구는 한글·숫자·기본 기호로만 쓴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "aligo", name = "api-key")
public class AligoClient {

    private static final String BASE_URL = "https://apis.aligo.in";

    /** 문자 API 성공값. 실패는 음수이고 사유는 {@code message} 에만 있다 */
    private static final int SUCCESS = 1;

    private final AligoProperties properties;
    private final RestClient restClient;

    public AligoClient(AligoProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory())
                .build();

        if (properties.testModeOrDefault()) {
            // 목업과 같은 함정이다 — 호출은 성공하고 과금도 없는데 수신자는 아무것도 못 받는다
            log.warn("★ 알리고가 테스트 모드다 — 실제 발송도 과금도 일어나지 않는다. "
                    + "운영 전환 시 ALIGO_TEST_MODE=false 로 바꿀 것.");
        } else {
            log.info("알리고 문자 연동이 활성화됐다: sender={}", properties.senderPhone());
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 가입 화면에서 사용자가 인증번호를 기다린다. 무한정 붙들지 않는다
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /**
     * 문자 한 통.
     *
     * @param title 제목. <b>LMS 에만 적용</b>된다 — SMS 로 보내면 무시된다
     * @throws AligoException 실패 시. <b>조용히 넘기지 않는다</b> — 사용자는 오지 않는 문자를 기다린다
     */
    public void send(String phone, String title, String message, MessageType type) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", properties.apiKey());
        form.add("user_id", properties.userId());
        form.add("sender", digits(properties.senderPhone()));
        form.add("receiver", digits(phone));
        form.add("msg", message);
        form.add("msg_type", type.name());
        if (type != MessageType.SMS && title != null && !title.isBlank()) {
            form.add("title", clampTitle(title));
        }
        if (properties.testModeOrDefault()) {
            form.add("testmode_yn", "Y");
        }

        Map<String, Object> body = post("/send/", form);
        int code = intOf(body.get("result_code"));
        if (code != SUCCESS) {
            throw new AligoException(code, String.valueOf(body.get("message")));
        }

        log.info("문자 발송: to={}, type={}, msgId={}, 성공 {}건 / 실패 {}건{}",
                mask(phone), body.get("msg_type"), body.get("msg_id"),
                body.get("success_cnt"), body.get("error_cnt"),
                properties.testModeOrDefault() ? " (테스트 모드 — 실제로 안 나간다)" : "");
    }

    /**
     * 남은 발송 가능 건수.
     *
     * <p>알리고는 <b>선불</b>이라 잔액이 0이면 발송이 실패한다 — 그런데 실패는
     * {@code result_code} 에만 나오므로(위 참고) <b>미리 보지 않으면 터진 뒤에 안다.</b>
     * 운영 준비 점검이 이걸 쓴다.
     */
    public Remain remain() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", properties.apiKey());
        form.add("user_id", properties.userId());

        Map<String, Object> body = post("/remain/", form);
        int code = intOf(body.get("result_code"));
        if (code != SUCCESS) {
            throw new AligoException(code, String.valueOf(body.get("message")));
        }
        return new Remain(intOf(body.get("SMS_CNT")), intOf(body.get("LMS_CNT")),
                intOf(body.get("MMS_CNT")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, MultiValueMap<String, String> form) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw new AligoException(0, "응답이 비어 있습니다.");
            }
            return body;
        } catch (AligoException e) {
            throw e;
        } catch (Exception e) {
            // 네트워크·타임아웃. 벤더 쪽 장애와 우리 설정 오류를 구분해 주려고 사유를 싣는다
            throw new AligoException(0, "알리고 호출 실패: " + e.getMessage());
        }
    }

    /** 응답 숫자가 문자열로 오는 칸이 섞여 있다 — 둘 다 받는다. */
    private static int intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 하이픈·공백을 떼고 숫자만 보낸다. 형식이 섞여 들어와도 호출은 같아야 한다. */
    private static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    /** 제목은 44바이트 제한이다. 넘기면 호출 자체가 거절된다. */
    private static String clampTitle(String title) {
        byte[] bytes = title.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 44) {
            return title;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (char c : title.toCharArray()) {
            int size = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (used + size > 44) {
                break;
            }
            sb.append(c);
            used += size;
        }
        return sb.toString();
    }

    static String mask(String phone) {
        String d = digits(phone);
        return d.length() < 4 ? "****" : "***-****-" + d.substring(d.length() - 4);
    }

    /** 장문 여부. <b>길이로 우리가 정한다</b> — 벤더 자동 전환에 맡기지 않는다(위 참고). */
    public enum MessageType {
        SMS, LMS;

        /** SMS 상한은 90바이트다. EUC-KR 기준이라 한글 2바이트로 센다. */
        public static MessageType of(String message) {
            int bytes = message == null ? 0
                    : message.getBytes(java.nio.charset.Charset.forName("EUC-KR")).length;
            return bytes > 90 ? LMS : SMS;
        }
    }

    public record Remain(int sms, int lms, int mms) {
    }
}
