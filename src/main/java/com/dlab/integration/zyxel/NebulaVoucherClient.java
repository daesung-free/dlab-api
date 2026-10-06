package com.dlab.integration.zyxel;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Nebula Voucher 연동 (2026-10-06 벤더 확정분).
 *
 * <h2>왜 목업과 나눠 두는가</h2>
 * {@code zyxel.nebula.api-key} 가 있을 때만 빈으로 올라온다. 키가 없는 로컬·CI 에서는
 * {@link LoggingNebulaClient} 가 그대로 남는다 — <b>자격증명이 없다고 다른 기능까지
 * 멈추면 안 된다.</b>
 *
 * <h2>★ 아직 실제 호출로 검증하지 못했다</h2>
 * API 키와 지점별 사이트 ID 를 받지 못해 <b>경로·필드명을 문서 기준으로만 맞췄다.</b>
 * 응답 해석을 {@link #codeOf}·{@link #idOf} 두 곳에 몰아 둔 이유가 그것이다 —
 * 실물과 어긋나면 그 두 곳만 고치면 된다. 받는 즉시 샘플 장비로 확인할 항목이다.
 *
 * <h2>풀에서 꺼내고, 없으면 채운다</h2>
 * 미사용 코드를 먼저 찾고 없을 때만 만든다. 매 건 생성하면 <b>발급 내역이 계속 쌓인다</b>
 * — 삭제해도 24시간 뒤에야 목록에서 빠진다.
 */
@Slf4j
@Component("realNebulaClient")
@ConditionalOnProperty(prefix = "zyxel.nebula", name = "api-key")
public class NebulaVoucherClient implements NebulaClient {

    private static final String API_KEY_HEADER = "X-ZyxelNebula-API-Key";

    /** 입력 형식이 고정이다 — 벤더 명시값이고 바꾸면 세션을 찾지 못한다 */
    private static final String LOGOUT_TARGET_FORMAT = "voucher@%s@voucher";

    private final NebulaProperties properties;
    private final RestClient restClient;

    public NebulaVoucherClient(NebulaProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrlOrDefault())
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .requestFactory(factory())
                .build();
        log.info("Nebula Voucher 연동이 활성화됐다: baseUrl={}", properties.baseUrlOrDefault());
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 학생이 신청 화면 앞에서 기다린다. 무한정 붙들고 있으면 안 된다
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Override
    public String assign(String siteId, int durationMinutes) {
        requireSite(siteId);

        String unused = findUnused(siteId);
        if (unused != null) {
            // ★ Duration 은 생성 시 정해진다. 풀 코드의 Duration 이 승인 시간과 다를 수 있어
            //   재생성이 필요한지는 샘플 장비로 확인할 항목이다(현재는 그대로 쓴다)
            log.info("Nebula Voucher 배정(풀): site={}, 분={}", siteId, durationMinutes);
            return unused;
        }

        String created = create(siteId, durationMinutes);
        log.info("Nebula Voucher 생성·배정: site={}, 분={}", siteId, durationMinutes);
        return created;
    }

    /**
     * 차단 — <b>두 호출을 모두</b> 보낸다.
     *
     * <p>logout 이 실패해도 delete 는 시도한다. 하나라도 되면 재접속은 막히고, 둘 다
     * 실패한 것과 <b>절반만 된 것은 위험도가 다르다</b> — 절반이라도 됐으면 다음 재시도에서
     * 나머지가 채워진다.
     */
    @Override
    public void revoke(String siteId, String voucherCode) {
        requireSite(siteId);
        if (voucherCode == null || voucherCode.isBlank()) {
            // 코드가 없으면 닫을 대상이 없다. 조용히 넘기면 열린 채로 남은 것을 못 찾는다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "배정된 Voucher 코드가 없어 차단할 수 없습니다.");
        }

        RuntimeException logoutFailure = null;
        try {
            logout(siteId, voucherCode);
        } catch (RuntimeException e) {
            logoutFailure = e;
            log.warn("Nebula 세션 종료 실패: site={}, code={}", siteId, voucherCode, e);
        }

        // ★ logout 이 실패해도 여기는 보낸다 — 재접속을 막는 쪽이 먼저다
        delete(siteId, voucherCode);

        if (logoutFailure != null) {
            // 이미 붙어 있는 세션이 살아 있을 수 있다. 호출자가 재시도하도록 올려 보낸다
            throw logoutFailure;
        }
        log.info("Nebula Voucher 회수: site={}, code={}", siteId, voucherCode);
    }

    // ── 호출 ──────────────────────────────────────────────────

    private String findUnused(String siteId) {
        JsonNode body = get("/v1/nebula/" + siteId + "/vouchers");
        for (JsonNode row : rows(body)) {
            if (isUnused(row)) {
                return codeOf(row);
            }
        }
        return null;
    }

    private String create(String siteId, int durationMinutes) {
        JsonNode body = restClient.post()
                .uri("/v1/nebula/{siteId}/vouchers", siteId)
                .body(Map.of(
                        "quantity", 1,
                        "duration", durationMinutes,
                        // ⚠️ API 의 maxDevices 는 1 로 고정이다. 코드당 2대(폰+태블릿)는
                        //    플랫폼에서 설정해야 하고 여기서는 바꿀 수 없다
                        "maxDevices", 1))
                .retrieve()
                .body(JsonNode.class);

        for (JsonNode row : rows(body)) {
            String code = codeOf(row);
            if (code != null) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.FIREWALL_UNAVAILABLE,
                "Nebula Voucher 생성 응답에서 코드를 찾지 못했습니다.");
    }

    private void logout(String siteId, String voucherCode) {
        restClient.post()
                .uri("/v1/nebula/{siteId}/ap/logout-user", siteId)
                .body(Map.of("user", LOGOUT_TARGET_FORMAT.formatted(voucherCode)))
                .retrieve()
                .toBodilessEntity();
    }

    /** 삭제는 코드가 아니라 id 를 받는다 — 목록에서 찾아 쓴다. */
    private void delete(String siteId, String voucherCode) {
        String id = findId(siteId, voucherCode);
        if (id == null) {
            // 이미 지워졌거나 24시간 자동 제거가 끝난 경우다. 목표는 이미 달성돼 있다
            log.info("Nebula Voucher 를 찾지 못했다(이미 삭제된 것으로 본다): site={}, code={}",
                    siteId, voucherCode);
            return;
        }
        restClient.delete()
                .uri("/v1/nebula/{siteId}/voucher/{id}", siteId, id)
                .retrieve()
                .toBodilessEntity();
    }

    private String findId(String siteId, String voucherCode) {
        JsonNode body = get("/v1/nebula/" + siteId + "/vouchers");
        for (JsonNode row : rows(body)) {
            if (voucherCode.equals(codeOf(row))) {
                return idOf(row);
            }
        }
        return null;
    }

    private JsonNode get(String path) {
        return restClient.get().uri(path).retrieve().body(JsonNode.class);
    }

    // ── 응답 해석 (실물과 어긋나면 여기만 고친다) ──────────────

    /** 목록이 배열로 오거나 {@code data}·{@code items} 아래 올 수 있어 셋 다 본다. */
    private static Iterable<JsonNode> rows(JsonNode body) {
        if (body == null) {
            return List.of();
        }
        if (body.isArray()) {
            return body;
        }
        for (String key : List.of("data", "items", "vouchers")) {
            JsonNode node = body.get(key);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return List.of();
    }

    private static String codeOf(JsonNode row) {
        return text(row, "code", "voucherCode", "voucher_code");
    }

    private static String idOf(JsonNode row) {
        return text(row, "id", "voucherId", "voucher_id");
    }

    /**
     * 미사용 판정.
     *
     * <p>★ <b>첫 사용 시각({@code activeTime})이 없으면 미사용이다.</b> 발급 시각과 다르다 —
     * 코드는 만들어진 때가 아니라 <b>학생이 처음 접속한 때부터</b> 시간을 쓴다.
     * 삭제 표시된 것은 제외한다.
     */
    private static boolean isUnused(JsonNode row) {
        if (row == null) {
            return false;
        }
        JsonNode deleted = row.get("deleted");
        if (deleted != null && deleted.asBoolean(false)) {
            return false;
        }
        String status = text(row, "status", "state");
        if (status != null) {
            return "unused".equalsIgnoreCase(status);
        }
        return text(row, "activeTime", "active_time") == null;
    }

    private static String text(JsonNode row, String... keys) {
        if (row == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode node = row.get(key);
            if (node != null && !node.isNull() && !node.asString().isBlank()) {
                return node.asString();
            }
        }
        return null;
    }

    private void requireSite(String siteId) {
        if (siteId == null || siteId.isBlank()) {
            // 지점 설정이 비어 있다는 뜻이다. 호출을 보내면 남의 사이트로 갈 여지가 있다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지점에 Nebula 사이트 ID가 설정되지 않았습니다.");
        }
    }
}
