package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.service.AttendanceBoardService;
import com.dlab.domain.attendance.service.AttendanceInterval;
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
 * 출결 행의 외출·조퇴 구간 (2026-10-13 디랩 피드백 5-5).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>하루 여러 번</b>을 표현한다 — 한 칸으로는 안 된다</li>
 *   <li><b>시간순</b>으로 정렬해 내린다 — 화면이 또 정렬하지 않게</li>
 *   <li><b>★★ 「안 돌아왔다」와 「복귀를 안 찍었다」를 가른다</b> — 담당자가 할 일이 다르다</li>
 *   <li><b>조퇴는 미복귀가 아니다</b> — 복귀가 없는 것이 정상이다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class AttendanceIntervalsTest {

    @Autowired AttendanceBoardService boardService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now().getYear();

        academy = new Academy("AE01", "구간지점", LocalTime.of(9, 0));
        em.persist(academy);

        Student person = new Student("AE-STU-1", "구간학생", "010-8000-0001");
        em.persist(person);
        enrollment = new StudentEnrollment(person, academy, year, "0001", "RF-AE01",
                GradeType.N_SU);
        em.persist(enrollment);

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        em.flush();
    }

    private void tag(AttendanceEventType type, LocalDate date, LocalTime at) {
        em.persist(new AttendanceTaggingLog(academy, enrollment, type,
                AttendanceSource.KIOSK_NFC,
                date.atTime(at).atZone(TimeConfig.KST).toInstant(), date));
        em.flush();
    }

    private List<AttendanceInterval> eventsOf(LocalDate date) {
        var rows = boardService.board(admin, academy.getId(), date, null);
        return rows.stream()
                .filter(r -> r.enrollmentId().equals(enrollment.getId()))
                .findFirst().orElseThrow().events();
    }

    @Test
    @DisplayName("★ 하루에 여러 번 나갔다 들어온 것이 순서대로 나온다")
    void listsMultipleOutingsInOrder() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        tag(AttendanceEventType.CHECK_IN, yesterday, LocalTime.of(8, 50));
        tag(AttendanceEventType.OUTING, yesterday, LocalTime.of(13, 20));
        tag(AttendanceEventType.RETURN, yesterday, LocalTime.of(14, 5));
        tag(AttendanceEventType.OUTING, yesterday, LocalTime.of(17, 0));
        tag(AttendanceEventType.RETURN, yesterday, LocalTime.of(17, 30));
        tag(AttendanceEventType.CHECK_OUT, yesterday, LocalTime.of(22, 0));

        var events = eventsOf(yesterday);

        assertThat(events).hasSize(2);
        // 시간순 — 화면이 등원~하원 사이에 끼워 그리므로 서버가 한 번만 정렬한다
        assertThat(events).extracting(AttendanceInterval::at).isSorted();
        assertThat(events).allMatch(e -> e.returnState() == AttendanceInterval.ReturnState.RETURNED);
        assertThat(events.get(0).returnedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★ 지난 날 복귀가 없으면 「기록 누락」이다 — 안 돌아온 것이 아니다")
    void pastDayWithoutReturnIsMissingRecord() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        tag(AttendanceEventType.CHECK_IN, yesterday, LocalTime.of(8, 50));
        tag(AttendanceEventType.OUTING, yesterday, LocalTime.of(13, 20));

        var events = eventsOf(yesterday);

        assertThat(events).hasSize(1);
        // 기록을 고쳐야 순공시간이 맞는다 — 그대로 두면 하원까지 밖에 있던 것으로 계산된다
        assertThat(events.get(0).returnState())
                .isEqualTo(AttendanceInterval.ReturnState.NO_RETURN_RECORD);
        assertThat(events.get(0).returnedAt()).isNull();
    }

    @Test
    @DisplayName("★★ 오늘 복귀가 없으면 「지금 밖에 있다」다 — 찾아야 하는 상태다")
    void todayWithoutReturnIsPending() {
        LocalDate today = LocalDate.now();
        tag(AttendanceEventType.CHECK_IN, today, LocalTime.of(8, 50));
        tag(AttendanceEventType.OUTING, today, LocalTime.of(13, 20));

        var events = eventsOf(today);

        // 같은 「복귀 없음」인데 조치가 다르다. returnedAt 만으로는 구분되지 않는다
        assertThat(events.get(0).returnState()).isEqualTo(AttendanceInterval.ReturnState.PENDING);
    }

    @Test
    @DisplayName("★ 조퇴는 미복귀가 아니다 — 복귀가 없는 것이 정상이다")
    void earlyLeaveDoesNotExpectReturn() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        tag(AttendanceEventType.CHECK_IN, yesterday, LocalTime.of(8, 50));
        tag(AttendanceEventType.EARLY_LEAVE, yesterday, LocalTime.of(19, 40));

        var events = eventsOf(yesterday);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AttendanceEventType.EARLY_LEAVE);
        // 「미복귀」로 표시하면 담당자가 학생을 찾으러 간다
        assertThat(events.get(0).returnState())
                .isEqualTo(AttendanceInterval.ReturnState.NOT_EXPECTED);
    }

    @Test
    @DisplayName("★ 사유외출(N)과 그냥 외출(D)이 갈린다")
    void separatesExcusedOuting() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        tag(AttendanceEventType.EXCUSED_OUTING, yesterday, LocalTime.of(10, 0));
        tag(AttendanceEventType.RETURN, yesterday, LocalTime.of(11, 0));
        tag(AttendanceEventType.OUTING, yesterday, LocalTime.of(15, 0));
        tag(AttendanceEventType.RETURN, yesterday, LocalTime.of(15, 30));

        var events = eventsOf(yesterday);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).excused()).isTrue();
        assertThat(events.get(1).excused()).isFalse();
    }

    @Test
    @DisplayName("등원·하원만 있으면 구간은 빈 목록이다 — null 이 아니다")
    void emptyWhenNoOuting() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        tag(AttendanceEventType.CHECK_IN, yesterday, LocalTime.of(8, 50));
        tag(AttendanceEventType.CHECK_OUT, yesterday, LocalTime.of(22, 0));

        assertThat(eventsOf(yesterday)).isEmpty();
    }
}
