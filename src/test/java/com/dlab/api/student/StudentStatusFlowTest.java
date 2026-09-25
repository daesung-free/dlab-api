package com.dlab.api.student;

import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.master.entity.LockerMaster;
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
 * 학생 상태 관리 (F-4.1-8).
 *
 * <p>핵심은 <b>상태만 바뀌지 않는다</b>는 것 — 퇴원이면 자리·계정까지 같이 정리돼야 하고,
 * 휴원이면 그대로 둬야 한다.
 */
@SpringBootTest
@Transactional
class StudentStatusFlowTest {

    private static final String PASSWORD = "status-test-password-1234";
    private static final short YEAR = 2026;

    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    MockMvc mvc;
    Academy academy;
    Long enrollmentId;
    Long lockerId;
    Long classId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        academy = new Academy("SL01", "상태테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee admin = new Employee(academy, "관리자");
        em.persist(admin);
        Account adminAccount = Account.forEmployee(admin, "SLADM", passwordEncoder.encode(PASSWORD), false);
        em.persist(adminAccount);
        em.flush();
        em.createNativeQuery("""
                        INSERT INTO account_role (account_id, role_id)
                        SELECT :accountId, id FROM role WHERE name = 'BRANCH_ADMIN'
                        """)
                .setParameter("accountId", adminAccount.getId()).executeUpdate();

        Student student = new Student("SLSTU001", "상태학생", "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, academy, YEAR, "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        // 앱 계정 — 퇴원 시 막혀야 한다
        em.persist(Account.forStudent(student, "SLSTU", passwordEncoder.encode(PASSWORD)));

        // 반 배정
        ClassMaster classMaster = new ClassMaster(academy, YEAR, "1반", ClassType.FIXED, null);
        em.persist(classMaster);
        em.persist(new ClassAssignment(academy, enrollment, classMaster, ClassType.FIXED));

        // 사물함 배정
        LockerMaster locker = new LockerMaster(academy, "A-01");
        locker.assign(enrollment);
        em.persist(locker);

        em.flush();
        enrollmentId = enrollment.getId();
        lockerId = locker.getId();
        classId = classMaster.getId();
    }

    private String token() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"SLADM","password":"%s"}""".formatted(PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).path("data").path("accessToken").asString();
    }

    private org.springframework.test.web.servlet.ResultActions change(String status, String reason)
            throws Exception {
        return mvc.perform(post("/api/v1/admin/students/{id}/status", enrollmentId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"%s","reason":"%s"}""".formatted(status, reason)));
    }

    @Test
    @DisplayName("★ 재등록 건을 지우면 직전 등록이 다시 현재가 된다 — 아무것도 current 가 아닌 상태가 남았다")
    void deleteRestoresPreviousEnrollment() throws Exception {
        Long studentId = em.createQuery("""
                SELECT e.student.id FROM StudentEnrollment e WHERE e.id = :id
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();

        change("WITHDRAWN", "자퇴").andExpect(status().isOk());
        String body = reEnroll(studentId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long reEnrolled = objectMapper.readTree(body).path("data").path("enrollmentId").asLong();
        em.flush();
        em.clear();

        mvc.perform(delete("/api/v1/admin/students/{id}", reEnrolled)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        // 되돌아온 쪽은 퇴원 상태라 current 가 되면 안 된다 — 퇴원생 카드가 태깅을 통과한다
        StudentEnrollment previous = em.find(StudentEnrollment.class, enrollmentId);
        assertThat(previous.isCurrent()).isFalse();

        // 사람은 남아야 한다 — 지운 것은 등록 건 하나뿐이다
        assertThat(em.find(Student.class, studentId).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("★ 재원 중인 학생은 재등록되지 않는다 — 같은 사람이 학번 둘로 갈린다")
    void reEnrollRequiresTerminalStatus() throws Exception {
        Long studentId = em.createQuery("""
                SELECT e.student.id FROM StudentEnrollment e WHERE e.id = :id
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();

        reEnroll(studentId).andExpect(status().isBadRequest());

        // 퇴원한 뒤에는 된다 — 재등록은 끝난 학생을 다시 받는 절차다
        change("WITHDRAWN", "자퇴").andExpect(status().isOk());
        em.flush();
        em.clear();
        reEnroll(studentId).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions reEnroll(Long studentId)
            throws Exception {
        Long academyId = em.createQuery("""
                SELECT e.academy.id FROM StudentEnrollment e WHERE e.id = :id
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        return mvc.perform(post("/api/v1/admin/students/{id}/re-enroll", studentId)
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"academyId":%d,"year":%d,"grade":"N_SU","track":"SCIENCE"}"""
                        .formatted(academyId, YEAR)));
    }

    @Test
    @DisplayName("★ 퇴원 → 휴원 → 재원으로 우회할 수 없다 — 두 번에 나눠 부르면 통과했다")
    void terminalStatusIsOneWay() throws Exception {
        change("WITHDRAWN", "자퇴").andExpect(status().isOk());

        // 재원 직행은 원래도 막혔지만, 휴원을 거치면 통과했다.
        change("LEAVE", "복귀 예정")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("재등록으로 처리")));

        change("ENROLLED", "착오")
                .andExpect(status().isBadRequest());

        // 종료끼리 정정도 막는다 — 이력에 한 줄만 남으면 왜 바뀌었는지 알 수 없다.
        change("EXPELLED", "정정")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 제적이 상태값에 있다 — 화면명이 '재원·휴원·퇴원·제적'인데 빠져 있었다")
    void expelledIsSupported() throws Exception {
        change("EXPELLED", "규정 위반")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student.enrollmentStatus").value("EXPELLED"));
    }

    @Test
    @DisplayName("★ 퇴원하면 반·사물함 배정이 풀리고 앱 계정이 막힌다 — 한 트랜잭션이다")
    void withdrawalCleansUpAssignments() throws Exception {
        change("WITHDRAWN", "본인 요청").andExpect(status().isOk());
        em.flush();
        em.clear();

        Long activeClass = em.createQuery("""
                SELECT COUNT(a) FROM ClassAssignment a
                WHERE a.enrollment.id = :id AND a.active = true
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        assertThat(activeClass).isZero();

        LockerMaster locker = em.find(LockerMaster.class, lockerId);
        assertThat(locker.getAssignedEnrollment()).isNull();

        AccountStatus accountStatus = em.createQuery("""
                SELECT a.status FROM Account a WHERE a.loginId = 'SLSTU'
                """, AccountStatus.class).getSingleResult();
        assertThat(accountStatus).isEqualTo(AccountStatus.WITHDRAWN);

        // 퇴원일이 남아야 환불 일할계산이 가능하다
        StudentEnrollment enrollment = em.find(StudentEnrollment.class, enrollmentId);
        assertThat(enrollment.getWithdrawalDate()).isNotNull();
    }

    @Test
    @DisplayName("★ 휴원은 자리를 비우지 않는다 — 돌아올 학생의 자리를 뺏으면 안 된다")
    void leaveKeepsAssignments() throws Exception {
        change("LEAVE", "건강").andExpect(status().isOk());
        em.flush();
        em.clear();

        Long activeClass = em.createQuery("""
                SELECT COUNT(a) FROM ClassAssignment a
                WHERE a.enrollment.id = :id AND a.active = true
                """, Long.class).setParameter("id", enrollmentId).getSingleResult();
        assertThat(activeClass).isEqualTo(1);

        LockerMaster locker = em.find(LockerMaster.class, lockerId);
        assertThat(locker.getAssignedEnrollment()).isNotNull();

        StudentEnrollment enrollment = em.find(StudentEnrollment.class, enrollmentId);
        assertThat(enrollment.getWithdrawalDate()).isNull();
    }

    @Test
    @DisplayName("★ 수료는 자리를 정리하되 퇴원일은 남기지 않는다 — 환불 대상이 아니다")
    void graduationCleansUpWithoutWithdrawalDate() throws Exception {
        change("GRADUATED", "정상 수료").andExpect(status().isOk());
        em.flush();
        em.clear();

        LockerMaster locker = em.find(LockerMaster.class, lockerId);
        assertThat(locker.getAssignedEnrollment()).isNull();

        StudentEnrollment enrollment = em.find(StudentEnrollment.class, enrollmentId);
        assertThat(enrollment.getWithdrawalDate()).isNull();
        assertThat(enrollment.isCurrent()).isFalse();
    }

    @Test
    @DisplayName("퇴원 상태에서 재원으로 되돌릴 수 없다 — 재등록으로 처리해야 한다")
    void cannotRevertToEnrolled() throws Exception {
        change("WITHDRAWN", "본인 요청").andExpect(status().isOk());
        em.flush();

        change("ENROLLED", "복귀").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("같은 상태로는 바꿀 수 없다")
    void sameStatusRejected() throws Exception {
        change("ENROLLED", "그대로").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 이력이 남는다 — 현재 상태만 보면 언제 왜 바뀌었는지 알 수 없다")
    void historyIsRecorded() throws Exception {
        change("LEAVE", "건강 문제").andExpect(status().isOk());
        em.flush();
        change("WITHDRAWN", "타 학원 이동").andExpect(status().isOk());
        em.flush();

        mvc.perform(get("/api/v1/admin/students/{id}/status-logs", enrollmentId)
                        .header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 최신순
                .andExpect(jsonPath("$.data[0].toStatus").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("LEAVE"))
                .andExpect(jsonPath("$.data[0].reason").value("타 학원 이동"));
    }

    @Test
    @DisplayName("★ 오등록 학생은 지울 수 있다 — 삭제 수단이 없어 테스트 학생이 재원생 수에 섞였다")
    void wronglyAdmittedStudentCanBeDeleted() throws Exception {
        mvc.perform(delete("/api/v1/admin/students/{id}", enrollmentId)
                        .header("Authorization", token()))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        // 목록에서 빠진다 — 물리 삭제가 아니라 is_deleted 다
        mvc.perform(get("/api/v1/admin/students").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.enrollmentId==%d)]".formatted(enrollmentId))
                        .isEmpty());
    }

    @Test
    @DisplayName("★★ 다닌 흔적이 있으면 삭제를 막는다 — 지우면 출결 기록이 주인을 잃는다")
    void studentWithHistoryCannotBeDeleted() throws Exception {
        StudentEnrollment enrollment = em.find(StudentEnrollment.class, enrollmentId);
        em.persist(new com.dlab.domain.attendance.entity.AttendanceTaggingLog(
                academy, enrollment,
                com.dlab.domain.attendance.entity.AttendanceEventType.CHECK_IN,
                com.dlab.domain.attendance.entity.AttendanceSource.KIOSK_NFC,
                java.time.Instant.now(), java.time.LocalDate.now()));
        em.flush();

        mvc.perform(delete("/api/v1/admin/students/{id}", enrollmentId)
                        .header("Authorization", token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STUDENT_HAS_HISTORY"));
    }
}
