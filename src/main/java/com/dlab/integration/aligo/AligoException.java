package com.dlab.integration.aligo;

/**
 * 알리고 호출 실패.
 *
 * <p><b>코드와 벤더 문구를 같이 들고 있는다.</b> 문서에 코드 전체 목록이 없고
 * {@code -99} 하나에 필수값 누락·포인트 부족·인증키 오류가 전부 몰려 있어서,
 * <b>사유는 {@code message} 에만 있다.</b> 코드만 로그에 남기면 원인을 알 수 없다.
 */
public class AligoException extends RuntimeException {

    private final int resultCode;

    public AligoException(int resultCode, String message) {
        super("[알리고 %d] %s".formatted(resultCode, message));
        this.resultCode = resultCode;
    }

    public int resultCode() {
        return resultCode;
    }
}
