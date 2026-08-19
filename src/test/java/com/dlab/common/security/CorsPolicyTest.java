package com.dlab.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * CORS 적용 범위.
 *
 * <p><b>왜 테스트로 고정하나</b> — CORS는 "반쯤 되어 있는" 상태가 가장 흔하고 가장 헷갈린다.
 * 실제로 이 프로젝트도 {@code OPTIONS}를 {@code permitAll}로 열어둔 채 CORS 설정이 없어서,
 * <b>preflight는 통과하는데 응답 헤더가 없어 브라우저가 차단하는</b> 상태였다.
 * 그 조합은 서버 로그에 아무 흔적도 남기지 않아 프론트에서만 원인 불명으로 보인다.
 *
 * <p>그래서 상태코드가 아니라 <b>{@code Access-Control-Allow-Origin} 헤더의 유무</b>를 본다.
 */
@SpringBootTest
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:*")
class CorsPolicyTest {

    @Autowired WebApplicationContext context;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("허용된 origin의 preflight에는 Access-Control-Allow-Origin이 붙는다")
    void preflightFromAllowedOriginGetsHeader() throws Exception {
        mvc.perform(options("/api/v1/app/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    /**
     * 포트를 정확히 박지 않고 패턴으로 둔 이유가 이것이다 — Vite는 5173이 쓰이는 중이면
     * 5174로 올라간다. 포트가 바뀔 때마다 서버 설정을 고쳐야 하면 아무도 안 고치고
     * CORS를 통째로 열어버리게 된다.
     */
    @Test
    @DisplayName("★ 로컬 포트가 바뀌어도 통과한다 — 패턴으로 두는 이유")
    void anyLocalPortIsAllowed() throws Exception {
        mvc.perform(options("/api/v1/app/auth/login")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5174"));
    }

    @Test
    @DisplayName("★ 허용 목록에 없는 origin은 차단된다")
    void unknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/app/auth/login")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /**
     * 키오스크는 브라우저가 아니라 CORS가 무의미하다. 그런데도 열어두면 노출 표면만 늘어난다.
     * DSA 호환 체인은 {@code cors.disable()}이라 헤더가 붙지 않아야 한다.
     */
    @Test
    @DisplayName("★ DSA 호환 구획에는 CORS를 열지 않는다")
    void dsaCompatSectionHasNoCors() throws Exception {
        mvc.perform(options("/kiosk/setAttendStd")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
