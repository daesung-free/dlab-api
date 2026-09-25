package com.dlab.domain.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.kiosk.entity.SeatLeaveEventType;
import com.dlab.domain.kiosk.entity.SeatLeaveLog;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.LeaveRow;
import com.dlab.domain.kiosk.service.SeatLeaveBoardService.Status;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 좌석 이탈 현황 (F-4.3-2).
 *
 * <p>지키려는 것 — <b>자동 마감을 복귀로 보이지 않을 것</b>, <b>담임은 맡은 학생만 볼 것</b>,
 * <b>학생을 못 찾은 이탈도 관리자에게는 보일 것</b>.
 */
@SpringBootTest
@Transactional
class SeatLeaveBoardTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired SeatLeaveBoardService service;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    short year;
    Instant now;
    LocalDate today;
    AuthPrincipal admin;
    AuthPrincipal teacher;
    StudentEnrollment mine;
    StudentEnrollment others;
    long rowId = 1;

    @BeforeEach
    void setUp() {
        now = Instant.now(clock);
        today = LocalDate.ofInstant(now, KST);
        year = (short) today.getYear();

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Teacher me = new Teacher(bundang, "김담임", null);
        Teacher other = new Teacher(bundang, "이담임", null);
        em.persist(me);
        em.persist(other);
        ClassMaster myClass = new ClassMaster(bundang, year, "1반", ClassType.FIXED, me);
        ClassMaster otherClass = new ClassMaster(bundang, year, "2반", ClassType.FIXED, other);
        em.persist(myClass);
        em.persist(otherClass);

        mine = enroll("DL-1", "김민지", "0001", "RF1", myClass);
        others = enroll("DL-2", "박서준", "0002", "RF2", otherClass);

        Account account = Account.forTeacher(me, "seat-leave-teacher", "x", false);
        em.persist(account);
        em.flush();

        teacher = AuthPrincipal.of(account.getId(), "TEACHER", bundang.getId(), List.of(Role.TEACHER), false);
        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(), List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo, String rfid, ClassMaster clazz) {
        Student s = new Student(code, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, bundang, year, stdNo, rfid, GradeType.N_SU);
        em.persist(e);
        em.persist(new ClassAssignment(bundang, e, clazz, ClassType.FIXED));
        return e;
    }

    private void log(StudentEnrollment e, SeatLeaveEventType type, Instant at) {
        em.persist(new SeatLeaveLog(bundang, year, rowId++, e,
                e == null ? "UNKNOWN" : e.getRfidNo(), e == null ? "9999" : e.getStudentNo(),
                "A", "A-01", type, at));
        em.flush();
    }

    private List<LeaveRow> history(AuthPrincipal who) {
        return service.history(who, null, today.minusDays(1), today, null);
    }

    @Test
    @DisplayName("이탈과 복귀가 한 행으로 짝지어지고 이탈 시간이 나온다")
    void pairsLeaveAndReturn() {
        Instant left = now.minus(Duration.ofMinutes(50));
        log(mine, SeatLeaveEventType.LEAVE, left);
        log(mine, SeatLeaveEventType.RETURN, left.plus(Duration.ofMinutes(12)));

        List<LeaveRow> rows = history(admin);

        assertThat(rows).hasSize(1);
        LeaveRow row = rows.get(0);
        assertThat(row.status()).isEqualTo(Status.RETURNED);
        assertThat(row.minutes()).isEqualTo(12);
        assertThat(row.name()).isEqualTo("김민지");
        assertThat(row.className()).isEqualTo("1반");
    }

    @Test
    @DisplayName("★ 자동 마감은 복귀가 아니다 — 이탈 시간을 내지 않는다")
    void autoCloseIsNotReturn() {
        Instant left = now.minus(Duration.ofMinutes(90));
        log(mine, SeatLeaveEventType.LEAVE, left);
        log(mine, SeatLeaveEventType.AUTO_CLOSE, left.plus(Duration.ofMinutes(80)));

        LeaveRow row = history(admin).get(0);

        assertThat(row.status()).isEqualTo(Status.AUTO_CLOSED);
        assertThat(row.minutes()).isNull();
        assertThat(service.current(admin, null, null)).isEmpty();
    }

    @Test
    @DisplayName("복귀가 없으면 지금 이탈 중이고 경과 시간이 나온다")
    void openLeaveIsCurrent() {
        log(mine, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(20)));

        List<LeaveRow> current = service.current(admin, null, null);

        assertThat(current).hasSize(1);
        assertThat(current.get(0).status()).isEqualTo(Status.OPEN);
        assertThat(current.get(0).minutes()).isBetween(19L, 21L);
    }

    @Test
    @DisplayName("복귀 없이 다음 이탈이 오면 앞 건은 기록 유실로 표시한다")
    void missingReturn() {
        log(mine, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(60)));
        log(mine, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(30)));

        List<LeaveRow> rows = history(admin);

        assertThat(rows).extracting(LeaveRow::status)
                .containsExactly(Status.OPEN, Status.NO_RETURN_RECORD);
    }

    @Test
    @DisplayName("★ 담임은 맡은 학생의 이탈만 보고, 학생을 못 찾은 건은 보지 않는다")
    void teacherSeesOnlyOwnStudents() {
        log(mine, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(10)));
        log(others, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(10)));
        log(null, SeatLeaveEventType.LEAVE, now.minus(Duration.ofMinutes(10)));

        assertThat(history(teacher)).extracting(LeaveRow::enrollmentId).containsExactly(mine.getId());

        List<LeaveRow> all = history(admin);
        assertThat(all).hasSize(3);
        assertThat(all).filteredOn(r -> !r.resolved())
                .singleElement()
                .satisfies(r -> assertThat(r.studentNo()).isEqualTo("9999"));
    }

    @Test
    @DisplayName("조회 기간은 최대 31일이다")
    void rangeLimit() {
        assertThatThrownBy(() -> service.history(admin, null, today.minusDays(31), today, null))
                .isInstanceOf(BusinessException.class);
    }
}
