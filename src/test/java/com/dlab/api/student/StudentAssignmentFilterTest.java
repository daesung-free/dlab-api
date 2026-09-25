package com.dlab.api.student;

import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.master.entity.LockerMaster;
import com.dlab.domain.master.entity.Scholarship;
import com.dlab.domain.user.entity.*;
import com.dlab.support.FacilityFixtures;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 학생 검색 — <b>미배정·장학 필터</b>.
 *
 * <p>지키려는 것은 넷이다.
 * <ol>
 *   <li><b>미배정이 전체 명단에서 나온다</b> — 반 배정 화면의 본체가 미배정 목록인데
 *       서버 조건이 없어 프론트가 받아온 페이지 안에서 걸렀다. 그건 전체 명단이 아니다</li>
 *   <li><b>배정된 학생은 확실히 빠진다</b> — 빠지지 않으면 배정 작업이 두 번 일어난다</li>
 *   <li><b>모순 조합은 빈 목록이 아니라 400</b> — 0건으로 돌려주면 화면이 조건을 잘못
 *       조합한 채 "해당 학생이 없다"로 읽고 조용히 넘어간다</li>
 *   <li><b>조건을 안 주면 기존과 같다</b> — 지금 쓰고 있는 명단 화면이 달라지면 안 된다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class StudentAssignmentFilterTest {

    private static final String PASSWORD = "student-filter-test-password-1234";

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Teacher homeroom;
    ClassMaster classMaster;
    StudyArea studyArea;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SF01", "필터테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "행정쌤");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SFADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId())
                .executeUpdate();

        homeroom = new Teacher(academy, "김담임", "010-1111-2222");
        em.persist(homeroom);
        classMaster = new ClassMaster(academy, (short) 2026, "가온반", ClassType.FIXED, homeroom);
        em.persist(classMaster);

        studyArea = new StudyArea(FacilityFixtures.mainBuilding(em, academy), "A", "A", "A구역", (short) 1);
        em.persist(studyArea);
        em.flush();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SFADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    /** 사람 + 등록 건. 학번은 호출자가 준다 — 채번 경로를 타지 않아야 순서에 안 흔들린다. */
    private StudentEnrollment student(String name, String studentNo) {
        Student s = new Student("SF" + studentNo.replace("-", ""), name, "010-3333-4444");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(
                s, academy, (short) 2026, studentNo, null, GradeType.N_SU);
        e.recordAdmission(LocalDate.of(2026, 3, 2));
        em.persist(e);
        return e;
    }

    private void assignClass(StudentEnrollment e) {
        em.persist(new ClassAssignment(academy, e, classMaster, ClassType.FIXED));
    }

    private void assignSeat(StudentEnrollment e, String seatCd) {
        SeatMaster seat = new SeatMaster(studyArea, seatCd, seatCd, seatCd + "번", 1, 1);
        em.persist(seat);
        em.persist(new SeatAssignment(academy, seat, e));
    }

    private void assignLocker(StudentEnrollment e, String lockerNo) {
        LockerMaster locker = new LockerMaster(academy, lockerNo);
        locker.assign(e);
        em.persist(locker);
    }

    private void grantScholarship(StudentEnrollment e, String type) {
        em.persist(new Scholarship(academy, e, type, new BigDecimal("30.00")));
    }

    // ── 반 미배정 (4-2) ──

    @Test
    @DisplayName("★ unassignedClass=true는 반이 없는 학생만 낸다 — 배정된 학생은 빠진다")
    void unassignedClassOnly() throws Exception {
        assignClass(student("반있는학생", "2026-0001"));
        student("반없는학생", "2026-0002");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("unassignedClass", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("반없는학생"))
                // ★ 총계도 함께 맞아야 한다 — 페이지 안에서 거르던 방식은 여기가 틀렸다
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    @DisplayName("unassignedClass=false는 반대로 배정된 학생만 낸다")
    void assignedClassOnly() throws Exception {
        assignClass(student("반있는학생", "2026-0011"));
        student("반없는학생", "2026-0012");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("unassignedClass", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("반있는학생"));
    }

    @Test
    @DisplayName("★ 반 배정을 해제(비활성)한 학생은 다시 미배정으로 걸린다")
    void releasedClassAssignmentCountsAsUnassigned() throws Exception {
        StudentEnrollment e = student("배정해제학생", "2026-0021");
        ClassAssignment a = new ClassAssignment(academy, e, classMaster, ClassType.FIXED);
        a.deactivate();
        em.persist(a);
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("unassignedClass", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("배정해제학생"));
    }

    // ── 좌석·사물함 미배정 ──

    @Test
    @DisplayName("★ 좌석·사물함도 같은 방식으로 미배정만 걸러진다")
    void unassignedSeatAndLocker() throws Exception {
        StudentEnrollment full = student("자리있는학생", "2026-0031");
        assignSeat(full, "A-01");
        assignLocker(full, "L-01");
        student("아무것도없는학생", "2026-0032");
        em.flush();
        String token = token();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("unassignedSeat", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("아무것도없는학생"));

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("unassignedLocker", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("아무것도없는학생"));

        // 반대 방향도 성립해야 한다
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("unassignedSeat", "false"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("자리있는학생"));
    }

    @Test
    @DisplayName("좌석을 반납(released_at)한 학생은 좌석 미배정으로 걸린다")
    void releasedSeatCountsAsUnassigned() throws Exception {
        StudentEnrollment e = student("좌석반납학생", "2026-0041");
        SeatMaster seat = new SeatMaster(studyArea, "A-99", "A-99", "99번", 1, 1);
        em.persist(seat);
        SeatAssignment a = new SeatAssignment(academy, seat, e);
        a.release(Instant.now());
        em.persist(a);
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("unassignedSeat", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("좌석반납학생"));
    }

    // ── 장학 (4-6) ──

    @Test
    @DisplayName("★ hasScholarship=true는 장학생만 낸다 — 장학생 명단 탭이 쓰는 조건이다")
    void scholarshipOnly() throws Exception {
        grantScholarship(student("장학생", "2026-0051"), "KICE_50");
        student("일반학생", "2026-0052");
        em.flush();
        String token = token();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("hasScholarship", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("장학생"))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026").param("hasScholarship", "false"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("일반학생"));
    }

    @Test
    @DisplayName("★ scholarshipType은 그 종류만 낸다 — 보유 여부를 따로 안 보내도 된다")
    void scholarshipByType() throws Exception {
        grantScholarship(student("평가원장학생", "2026-0061"), "KICE_50");
        grantScholarship(student("수능장학생", "2026-0062"), "CSAT_100");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026").param("scholarshipType", "CSAT_100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("수능장학생"));
    }

    // ── 모순 조합 ──

    @Test
    @DisplayName("★ 특정 반 + 반 미배정은 0건이 아니라 400이다 — 조용히 넘어가면 화면이 오해한다")
    void classIdWithUnassignedIsRejected() throws Exception {
        String token = token();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026")
                        .param("classId", String.valueOf(classMaster.getId()))
                        .param("unassignedClass", "true"))
                .andExpect(status().isBadRequest());

        // 담임도 반을 통해 걸리므로 같은 모순이다
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026")
                        .param("teacherId", String.valueOf(homeroom.getId()))
                        .param("unassignedClass", "true"))
                .andExpect(status().isBadRequest());

        // 반대로 "배정된 학생 중 이 반"은 모순이 아니다
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token)
                        .param("year", "2026")
                        .param("classId", String.valueOf(classMaster.getId()))
                        .param("unassignedClass", "false"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 장학 미보유 + 장학 종류 지정도 모순이라 400이다")
    void noScholarshipWithTypeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026")
                        .param("hasScholarship", "false")
                        .param("scholarshipType", "KICE_50"))
                .andExpect(status().isBadRequest());
    }

    // ── 회귀 ──

    @Test
    @DisplayName("★ 새 조건을 안 보내면 기존과 같이 전원이 나온다")
    void noNewConditionKeepsExistingBehaviour() throws Exception {
        assignClass(student("반있는학생", "2026-0071"));
        student("반없는학생", "2026-0072");
        grantScholarship(student("장학생", "2026-0073"), "KICE_50");
        em.flush();

        mvc.perform(get("/api/v1/admin/students").header("Authorization", token())
                        .param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.meta.totalElements").value(3));
    }
}
