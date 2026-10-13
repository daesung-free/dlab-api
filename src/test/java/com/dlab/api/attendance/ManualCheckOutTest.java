package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.attendance.service.ManualCheckOutService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
 * 하원 시각 수기 입력 (2026-10-13 디랩 피드백 4-2).
 *
 * <p>★★ 「하원이 없는 날의 순공시간」이 오래 미확정이던 항목이고 후보가 셋이었다 —
 * ①순공 0 ②지정 하원시각까지로 간주 ③관리자 보정. <b>디랩이 ③을 골랐다.</b>
 * 그래서 <b>서버가 추정하지 않는다.</b>
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>★ 등원이 없으면 거절한다</b> — 「온 적 없는데 갔다」가 된다</li>
 *   <li><b>★ 등원보다 이른 시각을 거절한다</b> — 순공이 음수가 되고 상벌점까지 흘러간다</li>
 *   <li><b>하원이 두 개가 되지 않는다</b> — 계산이 마지막 것을 써서 조용히 값이 달라진다</li>
 *   <li><b>출처가 `MANUAL` 로 남는다</b> — 키오스크가 찍은 것과 구분돼야 한다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class ManualCheckOutTest {

    @Autowired ManualCheckOutService service;
    @Autowired AttendanceTaggingLogRepository logRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment student;
    AuthPrincipal admin;
    LocalDate yesterday;

    @BeforeEach
    void setUp() {
        short year = (short) LocalDate.now().getYear();
        yesterday = LocalDate.now().minusDays(1);

        academy = new Academy("MC91", "하원보정지점", LocalTime.of(9, 0));
        em.persist(academy);

        Student person = new Student("MC-1", "미태그생", "010-6000-0001");
        em.persist(person);
        student = new StudentEnrollment(person, academy, year, "0001", "RF-MC1",
                GradeType.N_SU);
        em.persist(student);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private void tag(AttendanceEventType type, LocalTime at) {
        em.persist(new AttendanceTaggingLog(academy, student, type,
                AttendanceSource.KIOSK_NFC,
                yesterday.atTime(at).atZone(TimeConfig.KST).toInstant(), yesterday));
        em.flush();
    }

    private List<AttendanceTaggingLog> logs() {
        em.flush();
        em.clear();
        return logRepository.findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(
                student.getId(), yesterday);
    }

    @Test
    @DisplayName("★ 하원이 없던 날에 시각을 넣으면 기록이 생기고 순공이 계산된다")
    void recordsMissingCheckOut() {
        tag(AttendanceEventType.CHECK_IN, LocalTime.of(9, 0));

        int minutes = service.recordCheckOut(admin, student.getId(), yesterday,
                LocalTime.of(18, 0));

        // 9시간 = 540분. 교시 마스터가 없으면 급식·쉬는시간을 빼지 않는다
        assertThat(minutes).isPositive();
        var checkOuts = logs().stream()
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_OUT).toList();
        assertThat(checkOuts).hasSize(1);
        // ★ 키오스크가 찍은 것과 구분돼야 한다 — 앱이 「직접 등록됨」을 표시한다
        assertThat(checkOuts.get(0).getSource()).isEqualTo(AttendanceSource.MANUAL);
    }

    @Test
    @DisplayName("★★ 등원이 없으면 거절한다 — 「온 적 없는데 갔다」가 된다")
    void rejectsWithoutArrival() {
        assertThatThrownBy(() -> service.recordCheckOut(admin, student.getId(), yesterday,
                LocalTime.of(18, 0)))
                .hasMessageContaining("등원 기록이 없는");
    }

    @Test
    @DisplayName("★★ 등원보다 이른 시각은 거절한다 — 순공이 음수가 되고 상벌점까지 흘러간다")
    void rejectsBeforeArrival() {
        tag(AttendanceEventType.CHECK_IN, LocalTime.of(9, 0));

        assertThatThrownBy(() -> service.recordCheckOut(admin, student.getId(), yesterday,
                LocalTime.of(8, 30)))
                .hasMessageContaining("등원");
    }

    @Test
    @DisplayName("★ 이미 하원이 있으면 그 시각을 고친다 — 두 개면 계산이 마지막 것을 쓴다")
    void correctsExistingCheckOut() {
        tag(AttendanceEventType.CHECK_IN, LocalTime.of(9, 0));
        tag(AttendanceEventType.CHECK_OUT, LocalTime.of(22, 0));

        service.recordCheckOut(admin, student.getId(), yesterday, LocalTime.of(18, 0));

        var checkOuts = logs().stream()
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_OUT).toList();
        assertThat(checkOuts).hasSize(1);
        assertThat(LocalTime.ofInstant(checkOuts.get(0).getRecordedAt(), TimeConfig.KST))
                .isEqualTo(LocalTime.of(18, 0));
        // 고친 건도 MANUAL 이다 — 키오스크 값이 아니다
        assertThat(checkOuts.get(0).getSource()).isEqualTo(AttendanceSource.MANUAL);
    }

    @Test
    @DisplayName("앞날은 거절한다")
    void rejectsFutureDate() {
        assertThatThrownBy(() -> service.recordCheckOut(admin, student.getId(),
                LocalDate.now().plusDays(1), LocalTime.of(18, 0)))
                .hasMessageContaining("앞날");
    }

    @Test
    @DisplayName("★ 다른 지점 학생은 거절한다")
    void rejectsOtherBranch() {
        Academy other = new Academy("MC92", "남의지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();
        AuthPrincipal stranger = AuthPrincipal.of(2L, "EMPLOYEE", other.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> service.recordCheckOut(stranger, student.getId(), yesterday,
                LocalTime.of(18, 0)))
                .hasMessageContaining("지점");
    }
}
