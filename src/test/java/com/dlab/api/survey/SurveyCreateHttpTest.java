package com.dlab.api.survey;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 설문 생성 — <b>HTTP 경로</b>.
 *
 * <h2>★★ 서비스 테스트만으로는 이 부류가 안 잡힌다</h2>
 * 기존 설문 테스트는 전부 {@code surveyService} 를 직접 부른다. 그런데 2026-10-14 에
 * 터진 500 은 <b>요청 레코드 → 커맨드 변환</b>에서 났다 — 서비스에 닿기도 전이라
 * 서비스 테스트로는 영영 보이지 않는다.
 *
 * <p>원인은 <b>생략 가능한 칸을 primitive 로 받는 쪽에 넘긴 것</b>이었다.
 * {@code anonymousOrFalse()} 를 만들어 두고 정작 변환에서 {@code anonymous} 를 그대로
 * 넘겨, 그 칸을 <b>빼고 보내면 널 언박싱으로 500</b> 이 났다. 화면에는 「서버 오류」만 뜨고
 * <b>어느 칸 때문인지 보이지 않는다.</b>
 *
 * <p>⚠️ 같은 부류가 전에도 있었다 — 접수 중복 탐지의 {@code confirmedNotDuplicate} 가
 * 원시형이라 <b>그 칸을 안 보내는 기존 요청이 전부 400</b> 이 됐다. <b>생략 가능한 칸은
 * 래퍼로 받고 접근자에서 기본값을 준다.</b>
 */
@SpringBootTest
@Transactional
class SurveyCreateHttpTest {

    private static final String PASSWORD = "survey-http-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SV", "설문지점", LocalTime.of(9, 0));
        em.persist(academy);
        Employee staff = new Employee(academy, "설문담당");
        em.persist(staff);
        Account account = Account.forEmployee(
                staff, "SVADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(account);
        em.flush();
        grantRole(account.getId(), "BRANCH_ADMIN");
    }

    @Test
    @DisplayName("★★ anonymous 를 빼고 보내도 만들어진다 — 생략 가능한 칸이 500 을 내면 안 된다")
    void createsWithoutOptionalBooleans() throws Exception {
        mvc.perform(post("/api/v1/admin/surveys")
                        .header("Authorization", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surveyType":"GENERAL","scope":"BRANCH","academyId":%d,
                                 "title":"만족도 조사",
                                 "opensAt":"2026-10-14T00:00:00Z",
                                 "closesAt":"2026-12-31T23:59:00Z",
                                 "questions":[{"type":"TEXT","title":"의견","required":false}]}"""
                                .formatted(academy.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists());
    }

    @Test
    @DisplayName("★ 문항의 required 도 빼고 보낼 수 있다 — 원시형이면 생략만으로 400 이 된다")
    void createsWithoutQuestionRequired() throws Exception {
        mvc.perform(post("/api/v1/admin/surveys")
                        .header("Authorization", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surveyType":"GENERAL","scope":"BRANCH","academyId":%d,
                                 "title":"필수 생략 조사",
                                 "opensAt":"2026-10-14T00:00:00Z",
                                 "closesAt":"2026-12-31T23:59:00Z",
                                 "questions":[{"type":"TEXT","title":"의견"}]}"""
                                .formatted(academy.getId())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("anonymous 를 넣어 보내면 그대로 반영된다")
    void honoursAnonymousWhenGiven() throws Exception {
        mvc.perform(post("/api/v1/admin/surveys")
                        .header("Authorization", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surveyType":"GENERAL","scope":"BRANCH","academyId":%d,
                                 "title":"익명 조사","anonymous":true,
                                 "opensAt":"2026-10-14T00:00:00Z",
                                 "closesAt":"2026-12-31T23:59:00Z",
                                 "questions":[{"type":"TEXT","title":"의견","required":true}]}"""
                                .formatted(academy.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anonymous").value(true));
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SVADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer "
                + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }
}
