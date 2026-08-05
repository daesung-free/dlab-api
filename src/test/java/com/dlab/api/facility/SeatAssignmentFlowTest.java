package com.dlab.api.facility;

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
 * 좌석 배정·반납.
 *
 * <p>V2 스키마에 좌석당·학생당 현재 배정이 하나뿐이라는 부분 유니크가 걸려 있다.
 * <b>재배정 시 순서를 틀리면 제약에 걸린다</b>(반 배정에서 이미 한 번 겪은 함정).
 */
@SpringBootTest
@Transactional
class SeatAssignmentFlowTest {

    private static final String PASSWORD = "seat-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Long studyAreaId;
    Long seatA;
    Long seatB;
    Long enrollmentId;
    Long otherEnrollmentId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Academy academy = new Academy("SE01", "좌석테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SEADM", passwordEncoder.encode(PASSWORD));
        em.persist(adminAccount);
        em.flush();
        grantRole(adminAccount.getId(), "BRANCH_ADMIN");

        enrollmentId = createStudent(academy, "좌석학생", "SESTU01", "0001");
        otherEnrollmentId = createStudent(academy, "다른학생", "SESTU02", "0002");

        // 구역·좌석은 V2 스키마(다른 담당자) — 여기서는 배정만 검증하므로 직접 넣는다
        em.createNativeQuery("""
                INSERT INTO study_area (academy_id, area_cd, area_nm, sort_order)
                VALUES (:aid, 'A', '자습실A', 1)
                """).setParameter("aid", academy.getId()).executeUpdate();
        studyAreaId = ((Number) em.createNativeQuery(
                "SELECT id FROM study_area WHERE academy_id = :aid")
                .setParameter("aid", academy.getId()).getSingleResult()).longValue();

        seatA = insertSeat(academy.getId(), "A-01", 1, 1);
        seatB = insertSeat(academy.getId(), "A-02", 2, 1);
        em.flush();
    }

    private Long insertSeat(Long academyId, String seatCd, int x, int y) {
        em.createNativeQuery("""
                        INSERT INTO seat_master (academy_id, study_area_id, seat_cd, seat_nm, x_pos, y_pos)
                        VALUES (:aid, :sid, :cd, :cd, :x, :y)
                        """)
                .setParameter("aid", academyId).setParameter("sid", studyAreaId)
                .setParameter("cd", seatCd).setParameter("x", x).setParameter("y", y)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM seat_master WHERE seat_cd = :cd")
                .setParameter("cd", seatCd).getSingleResult()).longValue();
    }

    private Long createStudent(Academy academy, String name, String code, String no) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, academy, (short) 2026, no, null, GradeType.N_SU);
        em.persist(e);
        em.flush();
        return e.getId();
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
                                {"loginId":"SEADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private void assign(Long seatId, Long enrollment) throws Exception {
        mvc.perform(post("/api/v1/admin/seats")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatId":%d,"enrollmentId":%d}""".formatted(seatId, enrollment)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("좌석을 배정하면 구역 현황에 뜬다")
    void assignAndList() throws Exception {
        assign(seatA, enrollmentId);
        em.flush();

        mvc.perform(get("/api/v1/admin/seats").header("Authorization", token())
                        .param("studyAreaId", studyAreaId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].seatCd").value("A-01"))
                .andExpect(jsonPath("$.data[0].studentName").value("좌석학생"));
    }

    @Test
    @DisplayName("★ 이미 배정된 좌석에는 다른 학생을 넣을 수 없다")
    void occupiedSeatRejected() throws Exception {
        assign(seatA, enrollmentId);
        em.flush();

        mvc.perform(post("/api/v1/admin/seats")
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatId":%d,"enrollmentId":%d}""".formatted(seatA, otherEnrollmentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_ALREADY_OCCUPIED"));
    }

    @Test
    @DisplayName("★ 다른 좌석으로 옮기면 이전 좌석이 자동으로 반납된다")
    void reassignReleasesPrevious() throws Exception {
        assign(seatA, enrollmentId);
        em.flush();
        assign(seatB, enrollmentId);
        em.flush();

        // 학생의 현재 좌석은 하나뿐이어야 한다 (부분 유니크가 이걸 강제한다)
        Long activeCount = em.createQuery("""
                SELECT COUNT(a) FROM SeatAssignment a
                WHERE a.enrollment.id = :id AND a.releasedAt IS NULL
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        assertThat(activeCount).isEqualTo(1);

        // 비워진 A석에는 다른 학생이 들어갈 수 있어야 한다
        assign(seatA, otherEnrollmentId);
    }

    @Test
    @DisplayName("반납하면 좌석이 비고 이력은 남는다")
    void release() throws Exception {
        assign(seatA, enrollmentId);
        em.flush();

        mvc.perform(delete("/api/v1/admin/seats/students/{id}", enrollmentId)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();

        Long total = em.createQuery("""
                SELECT COUNT(a) FROM SeatAssignment a WHERE a.enrollment.id = :id
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        Long active = em.createQuery("""
                SELECT COUNT(a) FROM SeatAssignment a
                WHERE a.enrollment.id = :id AND a.releasedAt IS NULL
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();

        assertThat(total).isEqualTo(1);
        assertThat(active).isZero();
    }
}
