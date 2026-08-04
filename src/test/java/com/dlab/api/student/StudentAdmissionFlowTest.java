package com.dlab.api.student;

import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
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

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 학생 신규 접수 · 검색 · 재등록.
 *
 * <p>핵심은 <b>학번 채번</b>과 <b>재등록 시 사람이 유지되는지</b>다.
 * 재등록에서 새 사람을 만들면 상담 이력·신상기록부가 끊기고 동일인 추적이 불가능해진다.
 */
@SpringBootTest
@Transactional
class StudentAdmissionFlowTest {

    private static final String PASSWORD = "student-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("ST01", "학생테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "STADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        academyId = academy.getId();
    }

    private void grantRole(Long accountId, String roleName) {
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = :roleName
                        """)
                .setParameter("accountId", accountId).setParameter("roleName", roleName)
                .executeUpdate();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"STADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private String admit(String name) throws Exception {
        return mvc.perform(post("/api/v1/admin/students")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"%s","phone":"010-0000-0000",
                                 "grade":"N_SU","track":"SCIENCE"}""".formatted(academyId, name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★ 학번은 연도+4자리로 채번되고 1부터 증가한다")
    void studentNoIsGenerated() throws Exception {
        String first = admit("학생일");
        String second = admit("학생이");

        assertThat(objectMapper.readTree(first).path("data").path("studentNo").asString())
                .isEqualTo("2026-0001");
        assertThat(objectMapper.readTree(second).path("data").path("studentNo").asString())
                .isEqualTo("2026-0002");
    }

    @Test
    @DisplayName("학생 고유ID가 발급되고 헷갈리는 문자가 없다")
    void uniqueCodeIssued() throws Exception {
        String code = objectMapper.readTree(admit("학생일")).path("data").path("uniqueCode").asString();

        assertThat(code).hasSize(8);
        // 0/O, 1/I는 학부모가 눈으로 보고 입력하다 틀린다
        assertThat(code).doesNotContain("0").doesNotContain("O")
                .doesNotContain("1").doesNotContain("I");
    }

    @Test
    @DisplayName("이름·학번으로 검색된다")
    void search() throws Exception {
        admit("검색될학생");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "검색될"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value("검색될학생"));

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "0001"))
                .andExpect(jsonPath("$.data.content[0].studentNo").value("2026-0001"));
    }

    @Test
    @DisplayName("★ 재등록하면 사람은 그대로고 등록 건만 늘어난다")
    void reEnrollKeepsPerson() throws Exception {
        var first = objectMapper.readTree(admit("삼수생"));
        long studentId = first.path("data").path("studentId").asLong();
        String uniqueCode = first.path("data").path("uniqueCode").asString();
        em.flush();

        var re = objectMapper.readTree(mvc.perform(
                        post("/api/v1/admin/students/{id}/re-enroll", studentId)
                                .header("Authorization", token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"academyId":%d,"year":2027,"grade":"N_SU","track":"SCIENCE"}"""
                                        .formatted(academyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // 사람은 같고 고유ID도 그대로 — 상담 이력이 이어지는 근거
        assertThat(re.path("data").path("studentId").asLong()).isEqualTo(studentId);
        assertThat(re.path("data").path("uniqueCode").asString()).isEqualTo(uniqueCode);
        // 새 기수라 학번은 다시 1번
        assertThat(re.path("data").path("studentNo").asString()).isEqualTo("2027-0001");

        em.flush();
        Long enrollmentCount = em.createQuery("""
                SELECT COUNT(e) FROM StudentEnrollment e WHERE e.student.id = :id
                """, Long.class).setParameter("id", studentId).getSingleResult();
        Long currentCount = em.createQuery("""
                SELECT COUNT(e) FROM StudentEnrollment e WHERE e.student.id = :id AND e.current = true
                """, Long.class).setParameter("id", studentId).getSingleResult();

        assertThat(enrollmentCount).isEqualTo(2);
        assertThat(currentCount).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 검색은 현재 등록 건만 — 과거 기수가 섞이지 않는다")
    void searchExcludesPastEnrollments() throws Exception {
        long studentId = objectMapper.readTree(admit("이월학생"))
                .path("data").path("studentId").asLong();
        em.flush();

        mvc.perform(post("/api/v1/admin/students/{id}/re-enroll", studentId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"academyId":%d,"year":2027,"grade":"N_SU","track":"SCIENCE"}"""
                        .formatted(academyId)));
        em.flush();

        // 2026 기수로 검색하면 안 나와야 한다 (그 등록 건은 이력으로 내려갔다)
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("keyword", "이월"))
                .andExpect(jsonPath("$.data.content").isEmpty());

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2027").param("keyword", "이월"))
                .andExpect(jsonPath("$.data.content[0].name").value("이월학생"));
    }
}
