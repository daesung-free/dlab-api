package com.dlab.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 액추에이터 노출 범위.
 *
 * <p><b>왜 테스트로 고정하나</b> — 기본 체인이 {@code permitAll}이라 액추에이터가 그대로
 * 인터넷에 노출되는 구조였다. 노출 목록 설정으로도 막고 있지만 <b>설정 한 줄이 바뀌면
 * 그대로 뚫린다</b> — {@code env}가 열리면 DB 접속정보와 JWT 시크릿이 나간다.
 *
 * <p>이건 열려도 화면이 멀쩡해서 <b>뚫린 줄 아무도 모르는</b> 종류의 사고다.
 */
@SpringBootTest
class ActuatorExposureTest {

    @Autowired WebApplicationContext context;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("health는 열려 있다 — 외부 모니터가 인증 없이 찔러야 한다")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /**
     * <p><b>200이 아니라 "차단되지 않음"을 본다.</b> 이 경로가 실제로 응답하는지는
     * 프로필의 health 그룹 설정에 달려 있는데(테스트 리소스가 main의 설정을 통째로
     * 덮는다), 여기서 지켜야 할 것은 <b>보안이 막지 않는다</b>는 것이다.
     * 막히면 외부 모니터가 우리 장애를 영영 못 본다.
     */
    @Test
    @DisplayName("★ 키오스크 감시 창구는 보안이 막지 않는다 — 키오스크는 우리 장애를 알려주지 않는다")
    void kioskHealthIsNotBlocked() throws Exception {
        mvc.perform(get("/actuator/health/kiosk"))
                .andExpect(status().is(not(403)));
    }

    private static org.hamcrest.Matcher<Integer> not(int status) {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.is(status));
    }

    @Test
    @DisplayName("★ env는 막힌다 — 열리면 DB 접속정보와 JWT 시크릿이 그대로 나간다")
    void envIsBlocked() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 나머지 액추에이터도 전부 막힌다")
    void otherEndpointsAreBlocked() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/beans", "/actuator/heapdump",
                "/actuator/configprops", "/actuator/mappings", "/actuator/loggers"}) {
            mvc.perform(get(path))
                    .andExpect(status().isForbidden());
        }
    }
}
