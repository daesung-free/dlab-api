package com.dlab.integration.aligo;

import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 알리고 알림톡 API. <b>문자와 다른 서버·다른 성공값·다른 파라미터 이름을 쓴다.</b>
 *
 * <h2>★★ 성공은 {@code code == 0} 이다 — 문자는 {@code result_code == 1} 이다</h2>
 * 같은 벤더인데 다르다. {@link AligoClient}의 판정을 복사하면 <b>성공을 실패로</b> 읽어
 * 전부 예외가 되고, 반대로 쓰면 실패를 성공으로 읽는다.
 *
 * <h2>★ 파라미터 이름도 다르다</h2>
 * 문자는 {@code key}·{@code user_id}, 알림톡은 <b>{@code apikey}·{@code userid}</b>다
 * (밑줄이 없다). 잘못 보내면 「필수값 누락」이 아니라 <b>인증 오류</b>로 돌아와 키를
 * 의심하게 된다.
 *
 * <h2>★★ 충전이 문자와 별개다</h2>
 * 문자 잔액({@code /remain/})이 넉넉해도 알림톡 잔여({@code ALT_CNT})가 0이면 알림톡은
 * 못 나간다. 그리고 <b>알림톡은 SMS 폴백이 폐기됐다</b>(E-5) — 미등원 알림처럼 중요한
 * 알림이 <b>대체 수단 없이 그냥 멈춘다.</b> 그래서 운영 준비 점검이 이걸 따로 본다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${aligo.api-key:}' != ''")
public class AligoAlimtalkClient {

    private static final String BASE_URL = "https://kakaoapi.aligo.in";

    /** ★ 알림톡 성공값. 문자({@code 1})와 다르다 — 위 참고. */
    private static final int SUCCESS = 0;

    private final AligoProperties properties;
    private final RestClient restClient;

    /**
     * ★★ {@code @Autowired} 가 없으면 기동이 안 된다 — 생성자가 둘이라 Spring 이
     * 고르지 못하고 <b>무인자 생성자를 찾다 「No default constructor found」로 터진다.</b>
     *
     * <p>⚠️ 이 빈은 {@code @ConditionalOnExpression} 이라 <b>키가 설정된 환경에서만</b>
     * 만들어진다 — 로컬·CI 에는 키가 없어 빈이 아예 안 생기고, <b>운영에서만 터진다.</b>
     * 실제로 2026-10-22 배포가 이것으로 실패했다(크래시 루프).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AligoAlimtalkClient(AligoProperties properties) {
        this(properties, RestClient.builder().requestFactory(factory()));
    }

    /** 테스트 이음새 — 실패 응답은 실제 알리고로 일부러 만들어 낼 수 없다. */
    AligoAlimtalkClient(AligoProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /**
     * 남은 알림톡 건수.
     *
     * <p>응답의 {@code list.ALT_CNT}다. ⚠️ 같은 응답에 {@code SMS_CNT}도 들어 있는데
     * <b>문자 API 의 {@code /remain/} 과 값이 다르다</b> — 알림톡 쪽 숫자이므로
     * 문자 잔액으로 쓰지 말 것.
     */
    public int remainingAlimtalk() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("apikey", properties.apiKey());
        form.add("userid", properties.userId());

        Map<String, Object> body = post("/akv10/heartinfo/", form);
        return intOf(nested(body, "ALT_CNT"));
    }

    @SuppressWarnings("unchecked")
    private Object nested(Map<String, Object> body, String key) {
        Object list = body.get("list");
        // 템플릿 목록은 배열이고 heartinfo 는 객체다 — 같은 `list` 이름을 쓴다
        return list instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, MultiValueMap<String, String> form) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new AligoException(0, "알림톡 호출 실패: " + e.getMessage());
        }
        if (body == null) {
            throw new AligoException(0, "응답이 비어 있습니다.");
        }
        int code = intOf(body.get("code"));
        if (code != SUCCESS) {
            // ★ 실패 응답에도 숫자 칸이 들어 있다 — 코드를 안 보면 「잔여 0」으로 읽는다
            throw new AligoException(code, String.valueOf(body.get("message")));
        }
        return body;
    }

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
}
