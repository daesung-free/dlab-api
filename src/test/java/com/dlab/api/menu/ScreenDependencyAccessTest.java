package com.dlab.api.menu;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.Teacher;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 화면이 쓰는 <b>딸린 API</b> 접근.
 *
 * <h2>★★ 메뉴는 열려 있는데 그 화면의 API 가 막히면 빈 화면이 뜬다</h2>
 * 2026-10-14 운영 배포 직후 실제로 그랬다 — 담임에게 `timetable` 메뉴를 열어 뒀는데
 * 시간표가 쓰는 <b>교시·강의실·담당 교사</b>가 전부 403 이라 <b>격자가 비어 있었다.</b>
 * 메뉴가 보여서 들어갔는데 아무것도 없고, <b>이유가 화면에 보이지 않는다.</b>
 *
 * <h2>★ 메뉴 경로로는 잡히지 않는다</h2>
 * {@code menu.path_prefix} 는 그 화면의 <b>자기 API</b>만 가리킨다. 시간표 화면이
 * {@code /periods}·{@code /masters/rooms}·{@code /staff/teachers} 를 부르는 것처럼
 * <b>화면이 쓰는 API 는 자기 경로 밖에 있다</b> — 그래서 자동으로 대조할 수 없고
 * 여기에 <b>손으로 적어</b> 둔다.
 *
 * <h2>화면별로 쓰는 API — 프론트가 알려 준 목록(2026-10-14)</h2>
 * <pre>
 * 시간표        /periods · /masters/rooms · /staff/teachers · /classes · /timetables
 * 신상기록부     /student-profile-forms · /students · /files/private
 * Daily Report  /daily-reports · /classes · /attendance/study-time/recalculate
 * 순찰          /patrols/areas · /patrols/seats · /patrols/marks
 * 급식 명단      /meals/daily
 * 입학 원스톱    /students · /classes/&#123;id&#125;/students · /seats · /masters/scholarships
 * 반 배정        /classes · /staff/teachers · /students
 * </pre>
 *
 * <p>★ <b>화면이 어떤 API 를 쓰는지는 화면 쪽만 안다.</b> 새 화면이 붙거나 기존 화면이
 * 새 API 를 부르기 시작하면 이 목록과 아래 테스트에 <b>한 줄 추가</b>한다 — 그래야 낡지 않는다.
 *
 * <h2>⚠️ 읽기만 열려 있어야 한다</h2>
 * 이 API 들은 전부 <b>클래스 단위 권한</b>이 걸린 컨트롤러에 있다. 클래스째 넓히면
 * 담임이 <b>교시 마스터를 고치거나</b>(순공시간 기준이 흔들린다)
 * <b>계정 역할을 바꿀 수</b> 있게 된다. 그래서 메서드 단위로 연다.
 */
@SpringBootTest
@Transactional
class ScreenDependencyAccessTest {

    private static final String PASSWORD = "screen-dep-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    short year;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        year = (short) java.time.LocalDate.now().getYear();

        academy = new Academy("SD", "화면의존지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "의존담임", "010-5555-0001");
        em.persist(teacher);
        Account teacherAccount = Account.forTeacher(
                teacher, "SDTEACH", passwordEncoder.encode(PASSWORD), false);
        em.persist(teacherAccount);

        Employee viewer = new Employee(academy, "의존조회");
        em.persist(viewer);
        Account viewerAccount = Account.forEmployee(
                viewer, "SDVIEW", passwordEncoder.encode(PASSWORD), false);
        em.persist(viewerAccount);
        em.flush();

        grantRole(teacherAccount.getId(), "TEACHER");
        grantRole(viewerAccount.getId(), "READONLY");
    }

    @Test
    @DisplayName("★★ 담임의 시간표 화면이 쓰는 셋이 열려 있다 — 막히면 격자가 빈다")
    void teacherCanReadTimetableDependencies() throws Exception {
        String token = login("SDTEACH");

        // 교시 — 격자의 세로축
        mvc.perform(get("/api/v1/admin/periods")
                        .header("Authorization", token)
                        .param("academyId", String.valueOf(academy.getId()))
                        .param("year", String.valueOf(year)))
                .andExpect(status().isOk());

        // 강의실 — 편성이 고르는 값
        mvc.perform(get("/api/v1/admin/masters/rooms")
                        .header("Authorization", token)
                        .param("academyId", String.valueOf(academy.getId())))
                .andExpect(status().isOk());

        // 담당 교사 — 담임 드롭다운·시간표 담당 표시
        mvc.perform(get("/api/v1/admin/staff/teachers")
                        .header("Authorization", token)
                        .param("academyId", String.valueOf(academy.getId())))
                .andExpect(status().isOk());

        // ★★ 격자의 **본체**. 위 셋만 열면 **축은 그려지고 내용이 비는** 더 헷갈리는 모양이 된다
        mvc.perform(get("/api/v1/admin/timetables")
                        .header("Authorization", token)
                        .param("academyId", String.valueOf(academy.getId()))
                        .param("year", String.valueOf(year)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 담임이 시간표 편성·복사는 할 수 없다 — 자기 반을 보는 것과 지점 시간표를 짜는 것은 다르다")
    void teacherStillCannotEditTimetable() throws Exception {
        mvc.perform(post("/api/v1/admin/timetables/copy")
                        .header("Authorization", login("SDTEACH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"fromYear":%d,"toYear":%d}"""
                                .formatted(academy.getId(), year - 1, year)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 담임이 신상기록부 양식을 읽는다 — 작성은 담임이 하는데 양식을 못 읽으면 칸이 안 뜬다")
    void teacherCanReadProfileForms() throws Exception {
        mvc.perform(get("/api/v1/admin/student-profile-forms")
                        .header("Authorization", login("SDTEACH"))
                        .param("academyId", String.valueOf(academy.getId()))
                        .param("year", String.valueOf(year)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 조회 전용이 Daily Report 를 본다 — 집계이지 개별 기록이 아니다")
    void readonlyCanReadDailyReport() throws Exception {
        mvc.perform(get("/api/v1/admin/daily-reports")
                        .header("Authorization", login("SDVIEW"))
                        .param("academyId", String.valueOf(academy.getId()))
                        .param("from", java.time.LocalDate.now().toString())
                        .param("to", java.time.LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★★ 읽기만 열렸다 — 담임이 계정 역할을 바꿀 수는 없다")
    void teacherStillCannotChangeRoles() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/staff/accounts/{id}/roles", 1)
                        .header("Authorization", login("SDTEACH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roles":["SUPER_ADMIN"]}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★ 담임이 교시 마스터를 고칠 수는 없다 — 순공시간 계산의 기준이다")
    void teacherStillCannotEditPeriods() throws Exception {
        // ⚠️ **본문을 온전히 채운다.** `@Valid @RequestBody` 검증이 **권한보다 먼저** 돌아서,
        //    한 칸만 빠져도 403 이 아니라 400 이 나온다 — 그러면 이 테스트가
        //    「권한이 막았다」를 확인하지 못하고 **통과한 줄 착각**하게 된다
        mvc.perform(post("/api/v1/admin/periods")
                        .header("Authorization", login("SDTEACH"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"dayType":"WEEKDAY","periodNo":1,
                                 "periodType":"CLASS","startTime":"09:00:00","endTime":"09:50:00",
                                 "planable":true,"mandatory":true}"""
                                .formatted(academy.getId(), year)))
                .andExpect(status().isForbidden());
    }

    private String login(String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"%s"}""".formatted(loginId, PASSWORD)))
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
