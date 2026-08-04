package com.dlab.api.master;

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
 * 기숙사 방·배정.
 *
 * <p>사물함과 달리 <b>한 방에 여러 명</b>이라 정원 계산이 들어간다.
 */
@SpringBootTest
@Transactional
class DormFlowTest {

    private static final String PASSWORD = "dorm-test-password-1234";
    private static final short YEAR = 2026;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Long academyId;
    Long maleA;
    Long maleB;
    Long female;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("DM01", "기숙사테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account account = Account.forEmployee(admin, "DMADM", passwordEncoder.encode(PASSWORD));
        em.persist(account);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", account.getId()).executeUpdate();

        maleA = createStudent("남학생일", "DMS001", "0001", "M");
        maleB = createStudent("남학생이", "DMS002", "0002", "M");
        female = createStudent("여학생일", "DMS003", "0003", "F");
        academyId = academy.getId();
    }

    private Long createStudent(String name, String code, String no, String gender) {
        Student student = new Student(code, name, "010-0000-0000");
        student.updateProfile(null, null, null, gender, null);
        em.persist(student);
        StudentEnrollment e =
                new StudentEnrollment(student, academy, YEAR, no, null, GradeType.N_SU);
        em.persist(e);
        em.flush();
        return e.getId();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"DMADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private long createRoom(String roomNo, int capacity, String gender) throws Exception {
        String body = mvc.perform(post("/api/v1/admin/masters/dorms")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"academyId":%d,"year":%d,"building":"A동","roomNo":"%s",
                                 "capacity":%d,"gender":%s}"""
                                .formatted(academyId, YEAR, roomNo, capacity,
                                        gender == null ? "null" : "\"" + gender + "\"")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private void assign(long roomId, Long enrollmentId, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/admin/masters/dorms/{id}/assignments", roomId)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(enrollmentId)))
                .andExpect(status().is(expectedStatus));
        em.flush();
    }

    @Test
    @DisplayName("★ 정원이 차면 더 배정할 수 없다 — 사물함과 달리 인원을 세야 한다")
    void capacityIsEnforced() throws Exception {
        long room = createRoom("101", 2, "M");
        em.flush();

        assign(room, maleA, 200);
        assign(room, maleB, 200);

        Long third = createStudent("남학생삼", "DMS004", "0004", "M");
        mvc.perform(post("/api/v1/admin/masters/dorms/{id}/assignments", room)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(third)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DORM_ROOM_FULL"));
    }

    @Test
    @DisplayName("★ 방의 배정 성별과 다르면 거부한다 — 혼숙을 코드가 아니라 데이터로 막는다")
    void genderIsEnforced() throws Exception {
        long maleRoom = createRoom("102", 2, "M");
        em.flush();

        mvc.perform(post("/api/v1/admin/masters/dorms/{id}/assignments", maleRoom)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentId":%d}""".formatted(female)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DORM_GENDER_MISMATCH"));
    }

    @Test
    @DisplayName("성별 제한이 없는 방에는 누구든 들어간다")
    void roomWithoutGenderAcceptsAnyone() throws Exception {
        long room = createRoom("103", 2, null);
        em.flush();

        assign(room, female, 200);
        assign(room, maleA, 200);
    }

    @Test
    @DisplayName("★ 다른 방으로 옮기면 이전 방에서 자동으로 빠진다 — 두 방을 동시에 점유할 수 없다")
    void reassignReleasesPrevious() throws Exception {
        long first = createRoom("104", 2, "M");
        long second = createRoom("105", 2, "M");
        em.flush();

        assign(first, maleA, 200);
        assign(second, maleA, 200);
        em.clear();

        Long active = em.createQuery("""
                SELECT COUNT(a) FROM DormAssignment a
                WHERE a.enrollment.id = :id AND a.releasedAt IS NULL
                """, Long.class).setParameter("id", maleA).getSingleResult();
        assertThat(active).isEqualTo(1);

        // 이전 배정은 지워지지 않고 이력으로 남는다
        Long total = em.createQuery("""
                SELECT COUNT(a) FROM DormAssignment a WHERE a.enrollment.id = :id
                """, Long.class).setParameter("id", maleA).getSingleResult();
        assertThat(total).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 현재 인원보다 작게 정원을 줄일 수 없다 — 누가 나가야 할지 시스템이 정할 수 없다")
    void cannotShrinkBelowOccupancy() throws Exception {
        long room = createRoom("106", 3, "M");
        em.flush();
        assign(room, maleA, 200);
        assign(room, maleB, 200);

        mvc.perform(patch("/api/v1/admin/masters/dorms/{id}", room)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capacity":1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("퇴실하면 자리가 나고 이력은 남는다")
    void releaseFreesSlot() throws Exception {
        long room = createRoom("107", 1, "M");
        em.flush();
        assign(room, maleA, 200);

        mvc.perform(delete("/api/v1/admin/masters/dorms/assignments/students/{id}", maleA)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        assign(room, maleB, 200);

        Long history = em.createQuery("""
                SELECT COUNT(a) FROM DormAssignment a WHERE a.room.id = :id
                """, Long.class).setParameter("id", room).getSingleResult();
        assertThat(history).isEqualTo(2);
    }

    @Test
    @DisplayName("방 목록에 현재 인원이 함께 나온다")
    void roomListShowsOccupancy() throws Exception {
        long room = createRoom("108", 2, "M");
        em.flush();
        assign(room, maleA, 200);

        mvc.perform(get("/api/v1/admin/masters/dorms").header("Authorization", token())
                        .param("academyId", academyId.toString())
                        .param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].capacity").value(2))
                .andExpect(jsonPath("$.data[0].occupied").value(1));
    }
}
