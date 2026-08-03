package com.dlab.api.kiosk;

import lombok.Getter;

/**
 * DSA 호환 구획 전용 예외.
 *
 * <p>{@code BusinessException}을 쓰지 않는 이유: 그쪽은 {@code GlobalExceptionHandler}가
 * {@code ApiResponse}({@code success}/{@code error})로 변환하는데, 키오스크는 그 형태를
 * 파싱하지 못한다. 이 구획은 {@link DsaExceptionHandler}가 {@code code} 기반으로 따로 처리한다.
 */
@Getter
public class DsaApiException extends RuntimeException {

    private final DsaCode dsaCode;

    public DsaApiException(DsaCode dsaCode) {
        super(dsaCode.defaultMessage());
        this.dsaCode = dsaCode;
    }

    public DsaApiException(DsaCode dsaCode, String message) {
        super(message);
        this.dsaCode = dsaCode;
    }
}
