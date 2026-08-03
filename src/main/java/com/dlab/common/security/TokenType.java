package com.dlab.common.security;

/**
 * 토큰 종류. 클레임에 실어 두고 검증 시 대조한다 —
 * Refresh Token으로 API를 호출하는 것을 막기 위해서다.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
