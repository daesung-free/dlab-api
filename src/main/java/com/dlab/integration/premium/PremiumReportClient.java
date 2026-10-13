package com.dlab.integration.premium;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 더프리미엄 성적표 PDF 수신 (2026-10-13 규격 수령분).
 *
 * <h2>★ 엔드포인트 넷 중 하나만 쓴다</h2>
 * {@code GET /api/students/report} — 개인 성적표 PDF. 나머지 셋({@code students} ·
 * {@code subject} · {@code grade})은 성적 <b>데이터</b>인데 그건 <b>194열 엑셀</b>로 받는다
 * (2026-09-07 확정). 엑셀이 API 의 상위집합이라 바꿀 이유가 없다.
 *
 * <p>⚠️ {@link #ping} 만 {@code /api/students} 를 쓴다 — <b>연결 확인용이고 응답을
 * 저장하지 않는다.</b> 이름·휴대폰·생년월일·성별이 들어 있어 적재하면 개인정보가
 * 쓰지도 않을 곳에 쌓인다.
 *
 * <h2>★★ URL 문자열을 먼저 만들고 그 문자열로 서명·요청한다</h2>
 * {@code RestClient.uri(builder -> ...)} 를 쓰지 않는다. 빌더가 순서·인코딩을 바꾸면
 * <b>서명만 조용히 어긋나</b> 401 이 나는데 원인이 키인지 시계인지 서명인지 구분되지 않는다.
 * {@link URI#create}에 완성된 문자열을 그대로 넣는다.
 *
 * <p>⚠️ 기존 아웃바운드 클라이언트({@code KcpBuyLinkClient}·{@code NebulaVoucherClient})를
 * 복사하면 이 함정을 그대로 밟는다 — 그 둘은 서명이 없어 빌더를 써도 됐다.
 *
 * <h2>키가 없으면 빈이 올라오지 않는다</h2>
 * 개발서버 주소를 아직 받지 못했다. 자격증명이 없는 로컬·CI 에서 이 빈이 뜨면
 * <b>기동 때마다 연결 오류가 나고 다른 기능까지 흔들린다</b>.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "premium.report", name = {"api-key", "secret-key", "base-url"})
public class PremiumReportClient {

    private final PremiumProperties properties;
    private final Clock clock;
    private final RestClient restClient;

    public PremiumReportClient(PremiumProperties properties, Clock clock) {
        this(properties, clock, RestClient.builder().requestFactory(factory()));
        log.info("더프리미엄 성적표 연동이 활성화됐다: baseUrl={}", properties.baseUrl());
    }

    /**
     * 테스트에서 가짜 서버를 물릴 수 있게 빌더를 받는다.
     *
     * <p>★ 개발서버가 아직 없어 <b>계약을 가짜 서버로 고정</b>해야 한다 —
     * 특히 「서명한 문자열과 실제 URL 이 같은가」는 실물 없이 확인할 수 있는 몇 안 되는
     * 항목이고, 어긋나면 401 만 나와 <b>원인을 찾는 데 하루가 간다.</b>
     */
    PremiumReportClient(PremiumProperties properties, Clock clock, RestClient.Builder builder) {
        this.properties = properties;
        this.clock = clock;
        this.restClient = builder.build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // PDF 본문이라 조회보다 길게 준다
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /**
     * 개인 성적표 PDF.
     *
     * <p>★ <b>없으면 {@code null} 을 돌려준다 — 예외가 아니다.</b> 404 는 「아직 발행되지
     * 않음」일 수도 「그런 학생 없음」일 수도 있는데 <b>응답으로 구분되지 않는다</b>
     * (연구소 회신: <i>"구분값이 없습니다"</i>). 한 건이 없다고 배치가 멈추면
     * <b>뒤의 학생이 전부 안 받아진다</b> — 호출하는 쪽이 「미수신」으로 쌓고 계속 간다.
     *
     * @param examdt     시행일 {@code yyyyMMdd}
     * @param schoolId   학교코드. 디랩은 {@code 997xx} 체계다
     * @param bunho      ⚠️ <b>무엇을 넣는지 아직 확인되지 않았다</b> — 개발서버가 열리면
     *                   실제 호출로 확인할 항목이다. 추정하면 전 학생이 어긋난다
     */
    public byte[] downloadReport(String examdt, String schoolId, int grade,
                                 int classNumber, String bunho) {
        String path = "/api/students/report?examdt=%s&schoolId=%s&grade=%d&classNumber=%d&bunho=%s"
                .formatted(examdt, schoolId, grade, classNumber, bunho);
        var response = exchange(path, byte[].class);
        if (response == null) {
            log.info("성적표 미수신(404): examdt={}, schoolId={}, 반={}, 번호={}",
                    examdt, schoolId, classNumber, bunho);
        }
        return response;
    }

    /**
     * 연결 확인.
     *
     * <p>★ <b>키·서명·서버 시계만 맞는지</b> 본다. 개발서버가 열리면 가장 먼저 이것부터
     * 부른다 — 여기서 401 이 나면 성적표 수신을 짜 봐야 의미가 없다.
     *
     * <p>⚠️ <b>응답을 저장하지 않는다.</b> 이름·휴대폰·생년월일·성별이 들어 있다.
     *
     * @return 200 이면 {@code true}, 404(조회 결과 없음)여도 <b>인증은 통과한 것</b>이라 {@code true}
     */
    public boolean ping(String examdt, String schoolId, int grade, int classNumber,
                        String bunho) {
        String path = "/api/students?examdt=%s&schoolId=%s&grade=%d&classNumber=%d&bunho=%s"
                .formatted(examdt, schoolId, grade, classNumber, bunho);
        try {
            exchange(path, String.class);
            return true;
        } catch (BusinessException e) {
            log.warn("더프리미엄 연결 확인 실패: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ★ 서명과 요청이 <b>같은 문자열</b>을 쓰도록 한 곳에 모은다. 나눠 두면 한쪽만
     * 고쳐져 어긋나고, 그 증상이 401 이라 원인을 찾기 어렵다.
     *
     * @return 404 면 {@code null}. 그 밖의 실패는 예외
     */
    private <T> T exchange(String path, Class<T> type) {
        var headers = PremiumSignature.sign(properties.secretKey(), "GET", path, clock);
        return restClient.get()
                .uri(URI.create(properties.baseUrl() + path))
                .header("X-API-KEY", properties.apiKey())
                .header("X-TIMESTAMP", headers.timestamp())
                .header("X-SIGNATURE", headers.signature())
                .exchange((request, response) -> {
                    HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                    if (status == HttpStatus.NOT_FOUND) {
                        return null;
                    }
                    if (status == null || status.isError()) {
                        // ⚠️ 본문에 키가 실리지 않지만, 혹시 모르니 상태코드만 남긴다
                        throw new BusinessException(ErrorCode.REPORT_API_UNAVAILABLE,
                                "더프리미엄 API 오류: status=%s".formatted(status));
                    }
                    return response.bodyTo(type);
                });
    }
}
