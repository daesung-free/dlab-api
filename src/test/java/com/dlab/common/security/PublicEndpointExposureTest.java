package com.dlab.common.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 공개 구획의 경계.
 *
 * <p><b>왜 테스트로 고정하나</b> — {@code /api/v1/public/**} 한 줄이 느슨해지면
 * <b>학생·학부모 개인정보가 무인증으로 나간다.</b> 공개 페이지는 열려 있어야 하고
 * 관리자·앱 경로는 닫혀 있어야 하는데, 둘 다 같은 필터 체인 안의 한 줄에 달려 있다.
 *
 * <p>이건 넓게 열려도 화면이 멀쩡해서 <b>뚫린 줄 아무도 모르는</b> 종류의 사고다 —
 * 액추에이터 노출을 테스트로 묶어둔 것과 같은 이유다.
 *
 * <p>★ <b>응답 내용이 아니라 「인증이 막는가」를 본다.</b> 토큰은 가짜라도 되고, 공개
 * 경로는 그 가짜 토큰에 대해 <b>200 + {@code available:false}</b> 를 내려야 한다.
 */
@SpringBootTest
class PublicEndpointExposureTest {

    @Autowired WebApplicationContext context;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ───────────────── 열려 있어야 하는 것 ─────────────────

    /**
     * ★★ <b>404 가 아니라 200 + {@code available:false}</b>. 404 로 내리면 토큰을 두드려
     * 보는 쪽에 「이건 있다/없다」 힌트가 된다 — 도어락 공개 경로와 같은 판단이다.
     */
    @Test
    @DisplayName("★ 공개 안내문은 무인증으로 열린다 — 없는 토큰도 200 + available:false")
    void publicNoticeIsOpen() throws Exception {
        mvc.perform(get("/api/v1/public/notices/없는토큰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("★ 대기자 조회는 무인증으로 열린다")
    void publicWaitlistIsOpen() throws Exception {
        mvc.perform(get("/api/v1/public/waitlist/없는토큰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("★ 대기자 응답(POST)도 무인증으로 열린다 — accepted:false 로 거절한다")
    void publicWaitlistAnswerIsOpen() throws Exception {
        mvc.perform(post("/api/v1/public/waitlist/없는토큰")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"ACCEPT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false));
    }

    // ───────────────── 닫혀 있어야 하는 것 ─────────────────

    /**
     * ⚠️ <b>공개 구획을 열면서 같이 새어 나가기 가장 쉬운 곳</b>이다. 공개 페이지가
     * 공지·입학예약을 읽으므로 그 관리자 경로까지 함께 여는 실수가 난다.
     */
    @Test
    @DisplayName("★★ 관리자 공지는 무인증으로 열리지 않는다")
    void adminNoticeIsClosed() throws Exception {
        mvc.perform(get("/api/v1/admin/notices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★★ 관리자 대기자 목록은 무인증으로 열리지 않는다 — 이름·연락처가 그대로 나간다")
    void adminWaitlistIsClosed() throws Exception {
        mvc.perform(get("/api/v1/admin/admission-reservations").param("year", "2094"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★★ 공유 링크 발급은 관리자만 — 무인증으로 토큰을 만들 수 있으면 공개 범위를 남이 정한다")
    void shareIssuingIsClosed() throws Exception {
        mvc.perform(post("/api/v1/admin/notices/1/share"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/admission-reservations/1/share"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("★★ 앱 경로도 무인증으로 열리지 않는다")
    void appIsClosed() throws Exception {
        mvc.perform(get("/api/v1/app/notices"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ★ 접두어가 느슨해지지 않았는지 본다. {@code /api/v1/public**} 처럼 별 하나로 쓰면
     * {@code /api/v1/publicXxx} 까지 열린다 — 실수로 그렇게 고쳐도 테스트는 조용하므로
     * <b>닮은 경로가 닫혀 있다</b>는 쪽으로 고정한다.
     */
    @Test
    @DisplayName("★ 공개 접두어를 닮은 경로는 열리지 않는다")
    void lookAlikePrefixIsClosed() throws Exception {
        mvc.perform(get("/api/v1/publicity/notices/x"))
                .andExpect(status().isUnauthorized());
    }
}
