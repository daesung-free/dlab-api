package com.dlab.common.security;

import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 실패(401) 응답을 공통 포맷으로 내려준다.
 * Spring Security 기본 동작은 빈 본문이라, 클라이언트가 다른 오류와 구분하지 못한다.
 *
 * <p>만료와 위조를 다른 코드로 내려준다 — 앱 인터셉터가 401을 받으면 refresh를
 * 재시도하는데(A-C1), 위조 토큰까지 재시도하면 무의미한 왕복이 생긴다.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attr = request.getAttribute(JwtAuthenticationException.class.getName());
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
        if (attr instanceof JwtAuthenticationException e) {
            errorCode = e.getErrorCode();
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode));
    }
}
