package com.dlab.api.homepage.rest;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B안 구획의 예외 번역.
 *
 * <h2>★ 왜 따로 두는가</h2>
 * 입학예약 서비스는 A안(대성전산 규격)이 먼저라 실패를 {@link DsaApiException}으로 던진다.
 * B안은 {@code DsaExceptionHandler} 구획 밖이라 <b>그 예외가 아무에게도 잡히지 않고
 * 500으로 샌다</b> — 학원코드가 틀린 것은 <b>보낸 쪽 잘못인데</b> 서버 오류로 보인다.
 *
 * <p>여기서 <b>400 + 읽을 수 있는 이유</b>로 바꾼다. 서비스를 고쳐 예외 종류를 바꾸면
 * A안 응답의 {@code code} 숫자가 달라져 홈페이지 현행 연동이 깨지므로, 입구 쪽에서 번역한다.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.dlab.api.homepage.rest")
public class HomepageRestExceptionHandler {

    @ExceptionHandler(DsaApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleDsa(DsaApiException e) {
        log.warn("홈페이지 접수 거부: code={} {}", e.getDsaCode(), e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }
}
