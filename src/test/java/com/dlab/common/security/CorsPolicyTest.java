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
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:5173,http://localhost:3000")
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

    @Test
    @DisplayName("목록에 나열한 origin은 여러 개라도 각각 허용된다")
    void eachListedOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/v1/app/auth/login")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    /**
     * <b>와일드카드를 쓰지 않기로 한 결과가 이것이다.</b> 목록에 없는 포트는 같은 localhost 라도
     * 막힌다 — 범위가 실수로 넓어지지 않는 대신, 프론트 개발서버 포트가 바뀌면
     * ({@code Vite}가 5173 대신 5174로 뜨는 경우 등) {@code application-local.yml}에
     * 그 포트를 추가해야 한다.
     *
     * <p>이 테스트는 "불편함"을 고정한 것이 아니라 <b>목록 밖은 정말 막힌다</b>는 것을 고정한다.
     * 이게 깨지면 명시 목록으로 바꾼 의미가 없어진다.
     */
    @Test
    @DisplayName("★ 목록에 없는 포트는 localhost라도 막힌다 — 명시 목록으로 둔 결과")
    void unlistedLocalPortIsRejected() throws Exception {
        mvc.perform(options("/api/v1/app/auth/login")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
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
