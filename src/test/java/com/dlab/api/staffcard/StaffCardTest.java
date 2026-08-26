package com.dlab.api.staffcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.StaffAttendanceType;
import com.dlab.domain.attendance.service.DailyAttendanceConfirmService;
import com.dlab.domain.attendance.service.StaffAttendanceService;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.StaffEnrollmentService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 카드·근태 — 키오스크 출퇴근.
 *
 * <p><b>핵심은 "직원이 학생 쪽에 안 섞이는가"다.</b> 같은 등록 테이블을 쓰기 때문에
 * 조회 한 곳만 빠뜨려도 직원이 결석으로 확정되고 통계에 들어간다.
 */
@SpringBootTest
@Transactional
class StaffCardTest {

    @Autowired StaffEnrollmentService staffEnrollmentService;
    @Autowired StaffAttendanceService staffAttendanceService;
    @Autowired DailyAttendanceConfirmService confirmService;
    @Autowired StudentEnrollmentRepository enrollmentRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;      // 학생
    AuthPrincipal superAdmin;
    AuthPrincipal branchAdmin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);
        em.persist(new PeriodMaster(bundang, (short) today.getYear(), (short) 1, "자습",
                DayType.of(today), PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) today.getYear(),
                "2026-0001", "RF0001", GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        superAdmin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.SUPER_ADMIN), true);
        branchAdmin = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment staff(String name, String rfid) {
        StudentEnrollment e = staffEnrollmentService.register(
                superAdmin, bundang.getId(), name, "010-9999-0000", rfid);
        em.flush();
        return e;
    }

    // ── 등록 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 직원 학번은 9000번대다 — 4자리 키패드에서 학생과 구분되어야 한다")
    void staffNumberUsesReservedRange() {
        assertThat(staff("박행정", "RF9001").getStudentNo()).isEqualTo("2026-9001");
        assertThat(staff("최사감", "RF9002").getStudentNo()).isEqualTo("2026-9002");
    }

    @Test
    @DisplayName("학생 채번은 직원 대역에 영향받지 않는다")
    void studentNumberingIsUnaffected() {
        staff("박행정", "RF9001");
        em.flush();

        assertThat(enrollmentRepository.findMaxSequence(bundang.getId(), (short) today.getYear()))
                .isEqualTo(9001);   // 학생 채번은 이 값을 쓰지 않는다 — 직원 대역 조회와 분리돼 있다
    }

    // ── 격리 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 직원은 학생 목록에 안 나온다")
    void staffIsNotInStudentList() {
        staff("박행정", "RF9001");
        em.flush();
        em.clear();

        assertThat(enrollmentRepository.findCurrentByAcademyId(bundang.getId()))
                .extracting(e -> e.getStudent().getName())
                .containsExactly("김민지");
    }

    @Test
    @DisplayName("★★ 키오스크 동기화에는 직원도 나간다 — 카드를 인식해야 출퇴근을 찍는다")
    void kioskSyncIncludesStaff() {
        staff("박행정", "RF9001");
        em.flush();
        em.clear();

        assertThat(enrollmentRepository.findCurrentIncludingStaff(bundang.getId()))
                .extracting(e -> e.getStudent().getName())
                .containsExactlyInAnyOrder("김민지", "박행정");
    }

    @Test
    @DisplayName("★★ 확정 배치가 직원을 결석으로 만들지 않는다 — 조회가 기본으로 학생만 준다")
    void confirmBatchSkipsStaff() {
        StudentEnrollment staff = staff("박행정", "RF9001");
        em.flush();

        confirmService.confirm(bundang, today);
        em.flush();

        Long rows = em.createQuery("""
                        SELECT COUNT(d) FROM AttendanceDailyStatus d WHERE d.enrollment.id = :id
                        """, Long.class)
                .setParameter("id", staff.getId())
                .getSingleResult();

        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("직원 목록은 직원만 나온다")
    void staffListHasOnlyStaff() {
        staff("박행정", "RF9001");
        em.flush();
        em.clear();

        assertThat(staffEnrollmentService.list(superAdmin, bundang.getId()))
                .extracting(e -> e.getStudent().getName())
                .containsExactly("박행정");
    }

    // ── 근태 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 첫 태깅은 출근, 다음은 퇴근이다 — 교시가 없어 시각으로 가를 근거가 없다")
    void attendanceTogglesInAndOut() {
        StudentEnrollment staff = staff("박행정", "RF9001");
        em.flush();

        assertThat(staffAttendanceService.record(staff, today.atTime(8, 0)))
                .isEqualTo(StaffAttendanceType.IN);
        em.flush();
        assertThat(staffAttendanceService.record(staff, today.atTime(18, 0)))
                .isEqualTo(StaffAttendanceType.OUT);
        em.flush();
        // 하루에 여러 번 나갔다 와도 자연스럽게 이어진다
        assertThat(staffAttendanceService.record(staff, today.atTime(19, 0)))
                .isEqualTo(StaffAttendanceType.IN);
    }

    @Test
    @DisplayName("★★ 카드 이중 인식으로 출근이 퇴근으로 뒤집히지 않는다")
    void doubleTapDoesNotFlip() {
        StudentEnrollment staff = staff("박행정", "RF9001");
        em.flush();

        LocalDateTime at = today.atTime(8, 0);
        assertThat(staffAttendanceService.record(staff, at)).isEqualTo(StaffAttendanceType.IN);
        em.flush();
        assertThat(staffAttendanceService.record(staff, at.plusSeconds(2)))
                .isEqualTo(StaffAttendanceType.IN);
    }

    @Test
    @DisplayName("날이 바뀌면 다시 출근부터다")
    void newDayStartsWithCheckIn() {
        StudentEnrollment staff = staff("박행정", "RF9001");
        em.flush();

        staffAttendanceService.record(staff, today.minusDays(1).atTime(18, 0));
        em.flush();

        assertThat(staffAttendanceService.record(staff, today.atTime(8, 0)))
                .isEqualTo(StaffAttendanceType.IN);
    }

    @Test
    @DisplayName("★ 학생은 근태로 기록되지 않는다 — 태깅 분기가 잘못 타면 출결이 사라진다")
    void studentCannotBeRecordedAsStaff() {
        assertThatThrownBy(() -> staffAttendanceService.record(minji, today.atTime(8, 0)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("근태 조회는 지점 범위를 넘지 않는다")
    void attendanceSearchIsScoped() {
        assertThatThrownBy(() -> staffAttendanceService.search(
                branchAdmin, ilsan.getId(), null, today, today))
                .isInstanceOf(BusinessException.class);
    }

    // ── 퇴사 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 퇴사하면 동기화에서 빠진다 — 다음 동기화에 키오스크가 비활성 처리한다")
    void retiredStaffLeavesSync() {
        StudentEnrollment staff = staff("박행정", "RF9001");
        em.flush();

        staffEnrollmentService.retire(superAdmin, staff.getId());
        em.flush();
        em.clear();

        assertThat(enrollmentRepository.findCurrentIncludingStaff(bundang.getId()))
                .extracting(e -> e.getStudent().getName())
                .containsExactly("김민지");
    }
}
