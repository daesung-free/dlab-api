package com.dlab.api.student;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 검색 <b>정렬</b>.
 *
 * <p>지키려는 것은 셋이다. ①화면이 보낸 정렬이 실제로 반영되는가 ②허용하지 않은 필드를 보내도
 * 오류 없이 기본 정렬로 떨어지는가 ③정렬을 보내지 않으면 <b>기존 동작(학번 오름차순)</b>이 그대로인가.
 * ③이 깨지면 지금 쓰고 있는 명단 화면의 순서가 갑자기 달라진다.
 */
@SpringBootTest
@Transactional
class StudentSearchSortTest {

    private static final String PASSWORD = "student-sort-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long academyId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("SS01", "정렬테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SSADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId())
                .executeUpdate();

        academyId = academy.getId();

        // 이름 순서와 학번 순서가 어긋나게 넣는다 — 둘이 같으면 정렬이 먹었는지 알 수 없다.
        admit("다정렬");  // 2026-0001
        admit("가정렬");  // 2026-0002
        admit("나정렬");  // 2026-0003
        em.flush();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SSADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private void admit(String name) throws Exception {
        mvc.perform(post("/api/v1/admin/students")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":2026,"name":"%s","phone":"010-0000-0000",
                                 "grade":"N_SU","track":"SCIENCE"}""".formatted(academyId, name)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("이름 오름차순 정렬 요청이 반영된다")
    void sortByNameAsc() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("가정렬"))
                .andExpect(jsonPath("$.data[1].name").value("나정렬"))
                .andExpect(jsonPath("$.data[2].name").value("다정렬"));
    }

    @Test
    @DisplayName("이름 내림차순 정렬 요청이 반영된다")
    void sortByNameDesc() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("sort", "name,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("다정렬"))
                .andExpect(jsonPath("$.data[1].name").value("나정렬"))
                .andExpect(jsonPath("$.data[2].name").value("가정렬"));
    }

    @Test
    @DisplayName("연관 경로(student.name)로 보내도 같은 결과다")
    void sortByEntityPath() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("sort", "student.name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("가정렬"));
    }

    @Test
    @DisplayName("★ 허용 목록에 없는 필드는 오류가 아니라 무시되고 기본 정렬(학번)로 떨어진다")
    void unknownSortFieldIsIgnored() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("sort", "password,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentNo").value("2026-0001"))
                .andExpect(jsonPath("$.data[1].studentNo").value("2026-0002"))
                .andExpect(jsonPath("$.data[2].studentNo").value("2026-0003"));
    }

    @Test
    @DisplayName("★ 정렬을 주지 않으면 기존 동작 그대로 학번 오름차순이다")
    void defaultSortIsStudentNo() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].studentNo").value("2026-0001"))
                .andExpect(jsonPath("$.data[2].studentNo").value("2026-0003"));
    }

    @Test
    @DisplayName("★ 값이 겹치는 필드로 정렬해도 학번 tie-breaker 덕에 순서가 안정적이다")
    void tieBreakerKeepsOrderStable() throws Exception {
        // 세 명 모두 같은 학년(N_SU)이라 학년만으로는 순서가 정해지지 않는다.
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                            .param("year", "2026").param("sort", "grade,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].studentNo").value("2026-0001"))
                    .andExpect(jsonPath("$.data[1].studentNo").value("2026-0002"))
                    .andExpect(jsonPath("$.data[2].studentNo").value("2026-0003"));
        }
    }
}
