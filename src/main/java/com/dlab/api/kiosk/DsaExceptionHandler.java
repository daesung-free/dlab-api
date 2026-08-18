package com.dlab.api.kiosk;

import com.dlab.api.kiosk.dto.DsaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * DSA 호환 구획 전용 예외 핸들러.
 *
 * <p>범위를 안 좁히면 {@code GlobalExceptionHandler}와 충돌해서 어느 쪽이 잡을지가
 * 스캔 순서에 좌우된다.
 *
 * <p><b>실패해도 HTTP 200으로 내려간다.</b> 키오스크는 상태코드를 안 보고
 * 본문의 {@code code}로만 판정한다. 4xx/5xx를 주면 클라이언트가 본문을 파싱하기 전에
 * 예외로 처리해버려서 {@code code 113}(선택 요구) 같은 정상 분기가 통째로 유실된다.
 *
 * <h2>★ 하위 패키지를 하나씩 적는 이유</h2>
 * {@code com.dlab.api.kiosk} 통째로 잡으면 <b>DSA 호환이 아닌 신규 구획까지 덮는다</b>
 * ({@code seatleave} 등 {@code /api/v1/kiosk/**}). 그러면 신규 엔드포인트의 오류가
 * HTTP 200 + {@code code} 형식으로 나가 <b>정상 응답과 구분되지 않는다.</b>
 *
 * <p><b>새 DSA 컨트롤러를 다른 하위 패키지에 만들면 여기에 추가해야 한다.</b>
 * 빠뜨리면 그 엔드포인트만 우리 형식으로 응답해 키오스크 파싱이 깨진다 —
 * {@code DsaHandlerCoverageTest}가 이 누락을 잡는다.
 */
@Slf4j
@RestControllerAdvice(basePackages = {
        "com.dlab.api.kiosk.attendance",
        "com.dlab.api.kiosk.auth",
        "com.dlab.api.kiosk.meal",
        "com.dlab.api.kiosk.penalty",
        "com.dlab.api.kiosk.receipt",
        "com.dlab.api.kiosk.seat",
        "com.dlab.api.kiosk.stats",
        "com.dlab.api.kiosk.student"
})
public class DsaExceptionHandler {

    @ExceptionHandler(DsaApiException.class)
    public ResponseEntity<DsaResponse> handleDsaApi(DsaApiException e) {
        // 토큰 만료는 정상 흐름(키오스크가 refresh 후 재시도)이라 로그를 남기지 않는다.
        if (e.getDsaCode() != DsaCode.TOKEN_EXPIRED) {
            log.info("DSA 응답 코드 {} - {}", e.getDsaCode().value(), e.getMessage());
        }
        return ResponseEntity.ok(DsaResponse.error(e.getDsaCode(), e.getMessage()));
    }

    /**
     * 예상 못 한 오류. 키오스크에는 토큰 만료로 위장하지 않고 명시적 실패를 준다.
     *
     * <p>여기 걸리는 건 우리 버그다. 키오스크 쪽 폴백(급식 실패 시 전부 허용 등)이
     * 이걸 조용히 덮어버리므로 반드시 ERROR로 남겨야 한다 — 우리가 모르면 아무도 모른다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<DsaResponse> handleUnexpected(Exception e) {
        log.error("DSA 호환 구획 처리 실패", e);
        return ResponseEntity.status(HttpStatus.OK)
                .body(DsaResponse.error(DsaCode.NO_APPROVAL, "처리 중 오류가 발생했습니다."));
    }
}
