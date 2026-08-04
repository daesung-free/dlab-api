package com.dlab.common.security;

import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인가 실패(403) 응답을 공통 포맷으로 내려준다.
 *
 * <p>검수 시나리오 S-1이 <b>"권한 없는 API 직접 호출도 차단"</b>을 확인한다 —
 * 화면 가드만으로는 통과하지 못하고 서버가 실제로 막아야 한다.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorCode.FORBIDDEN));
    }
}
