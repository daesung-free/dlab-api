package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceModification;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.attendance.service.AttendanceBoardService;
import com.dlab.domain.attendance.service.AttendanceBoardService.ScreenStatus;
import com.dlab.domain.attendance.service.AttendanceCorrectionService;
import com.dlab.domain.attendance.service.DailyAttendanceConfirmService;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * 관리자 출결 정정 (F-4.3-1 "개별 수정").
 *
 * <p>핵심은 <b>원장을 고치지 않는다</b>와 <b>배치가 정정을 되돌리지 않는다</b> 둘이다.
 */
@SpringBootTest
@Transactional
class AttendanceCorrectionTest {

    @Autowired AttendanceCorrectionService correctionService;
    @Autowired AttendanceBoardService boardService;
    @Autowired DailyAttendanceConfirmService confirmService;
    @Autowired AttendanceTaggingLogRepository taggingLogRepository;
    @Autowired AttendanceDailyStatusRepository dailyStatusRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    LocalDate day;

    @BeforeEach
    void setUp() {
        day = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 1, "자습",
                DayType.of(day), PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));

        Student student = new Student("DL-1", "김민지", "010-0000-0000");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private void kioskTag(AttendanceEventType type, int hour, int minute) {
        em.persist(new AttendanceTaggingLog(bundang, minji, type, AttendanceSource.KIOSK_NFC,
                day.atTime(hour, minute).atZone(clock.getZone()).toInstant(), day));
        em.flush();
    }

    private ScreenStatus screenStatus() {
        em.flush();
        em.clear();
        return boardService.board(admin, null, day, null).get(0).status();
    }

    private AttendanceDailyStatus confirmed() {
        em.flush();
        em.clear();
        return dailyStatusRepository
                .findByEnrollmentIdAndAttendanceDate(minji.getId(), day).orElseThrow();
    }

    @Test
    @DisplayName("★ 태깅 보정은 원장에 MANUAL 행을 더한다 — 키오스크 행은 그대로다")
    void manualTaggingIsAddedNotOverwritten() {
        kioskTag(AttendanceEventType.LATE, 9, 30);

        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_OUT, LocalTime.of(22, 0), "하원 태깅 누락");
        em.flush();
        em.clear();

        List<AttendanceTaggingLog> logs = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day);

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getSource()).isEqualTo(AttendanceSource.KIOSK_NFC);
        assertThat(logs.get(1).getSource()).isEqualTo(AttendanceSource.MANUAL);
    }

    @Test
    @DisplayName("★ 등원 보정하면 결석이 풀린다 — 상태를 직접 고르지 않는다")
    void addingArrivalClearsAbsence() {
        // 아무 태깅도 없다 → 결석
        confirmService.confirm(bundang, day);
        assertThat(confirmed().getFinalStatus()).isEqualTo(DailyStatus.ABSENT);

        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");

        assertThat(confirmed().getFinalStatus()).isEqualTo(DailyStatus.PRESENT);
    }

    @Test
    @DisplayName("★ 보정 즉시 화면에 반영된다 — 배치를 기다리면 그날 밤까지 결석으로 보인다")
    void correctionIsVisibleImmediately() {
        confirmService.confirm(bundang, day);

        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");

        assertThat(screenStatus()).isEqualTo(ScreenStatus.ON_TIME);
    }

    @Test
    @DisplayName("★ 태깅 보정은 배치가 다시 돌아도 유지된다 — 원장에 있으니 같은 결과가 나온다")
    void taggingCorrectionSurvivesBatch() {
        confirmService.confirm(bundang, day);
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");
        em.flush();
        em.clear();

        confirmService.confirm(bundang, day);

        assertThat(confirmed().getFinalStatus()).isEqualTo(DailyStatus.PRESENT);
    }

    @Test
    @DisplayName("★★ 상태 정정을 배치가 되돌리지 않는다 — 되돌리면 새벽에 조용히 뒤집힌다")
    void statusCorrectionIsNotRevertedByBatch() {
        confirmService.confirm(bundang, day);   // 태깅 없음 → 결석

        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.PRESENT, false, "키오스크 장애로 미기록");
        em.flush();
        em.clear();

        confirmService.confirm(bundang, day);   // 다음 날 새벽 배치

        assertThat(confirmed().getFinalStatus()).isEqualTo(DailyStatus.PRESENT);
        assertThat(confirmed().isManuallyModified()).isTrue();
    }

    @Test
    @DisplayName("정정분에도 순공시간은 계속 갱신된다 — 원장에서 파생되는 값이라 관리자가 고른 게 아니다")
    void studyMinutesStillUpdatesOnCorrectedRow() {
        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.PRESENT, false, "키오스크 장애");
        em.flush();

        kioskTag(AttendanceEventType.CHECK_IN, 8, 0);
        kioskTag(AttendanceEventType.CHECK_OUT, 12, 0);
        confirmService.confirm(bundang, day);

        assertThat(confirmed().getStudyMinutes()).isEqualTo(240);
    }

    @Test
    @DisplayName("★ 확정 전(오늘)도 정정할 수 있다 — 행이 없으면 만든다")
    void canCorrectBeforeBatchConfirms() {
        // attendance_daily_status가 아직 없다(배치는 새벽 2시)
        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.LATE, true, "사유 승인 반영");

        assertThat(confirmed().getFinalStatus()).isEqualTo(DailyStatus.LATE);
        assertThat(confirmed().isExcused()).isTrue();
    }

    @Test
    @DisplayName("★ 정정 이력에 이전 값이 남는다 — 행에는 마지막 값만 남아 답할 수 없다")
    void historyKeepsBeforeValue() {
        confirmService.confirm(bundang, day);
        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.PRESENT, false, "키오스크 장애로 미기록");
        em.flush();
        em.clear();

        List<AttendanceModification> history =
                correctionService.history(admin, minji.getId(), day);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getBeforeStatus()).isEqualTo(DailyStatus.ABSENT);
        assertThat(history.get(0).getAfterStatus()).isEqualTo(DailyStatus.PRESENT);
        assertThat(history.get(0).getReason()).isEqualTo("키오스크 장애로 미기록");
    }

    @Test
    @DisplayName("여러 번 고쳐도 중간 이력이 남는다")
    void everyCorrectionIsKept() {
        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.PRESENT, false, "1차 정정");
        em.flush();
        correctionService.correctStatus(admin, minji.getId(), day,
                DailyStatus.ABSENT, false, "확인 결과 미등원");
        em.flush();
        em.clear();

        assertThat(correctionService.history(admin, minji.getId(), day)).hasSize(2);
    }

    @Test
    @DisplayName("태깅 보정도 이력에 남는다")
    void taggingCorrectionIsAlsoLogged() {
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");
        em.flush();
        em.clear();

        AttendanceModification logged =
                correctionService.history(admin, minji.getId(), day).get(0);

        assertThat(logged.getAddedEvent()).isEqualTo(AttendanceEventType.CHECK_IN);
        assertThat(logged.getBeforeStatus()).isNull();   // 상태 정정이 아니다
    }

    @Test
    @DisplayName("★ 사유 없는 정정은 거부 — 이력이 감사 자료가 되지 못한다")
    void reasonIsRequired() {
        assertThatThrownBy(() -> correctionService.correctStatus(
                admin, minji.getId(), day, DailyStatus.PRESENT, false, "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("사유");
    }

    @Test
    @DisplayName("★ 미래 날짜는 거부 — 미등원 알림·결석 확정이 어긋난다")
    void futureDateIsRejected() {
        assertThatThrownBy(() -> correctionService.addTagging(
                admin, minji.getId(), day.plusDays(1),
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "선반영"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("미래");
    }

    @Test
    @DisplayName("같은 시각 같은 태깅을 두 번 넣지 못한다 — 버튼 두 번 누르기")
    void duplicateTaggingIsRejected() {
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");
        em.flush();

        assertThatThrownBy(() -> correctionService.addTagging(
                admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("조퇴 후 재등원처럼 같은 이벤트가 하루에 여러 번인 건 막지 않는다")
    void sameEventAtDifferentTimeIsAllowed() {
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(8, 30), "카드 미소지");
        em.flush();

        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_IN, LocalTime.of(14, 0), "조퇴 후 재등원");
        em.flush();
        em.clear();

        assertThat(taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day))
                .hasSize(2);
    }

    @Test
    @DisplayName("★ 다른 지점 학생은 정정할 수 없다")
    void otherAcademyStudentIsRejected() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> correctionService.correctStatus(
                ilsanAdmin, minji.getId(), day, DailyStatus.PRESENT, false, "정정"))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // 태깅 삭제 (디랩 1021 피드백 2-1)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 지운 태깅은 순공시간에서 빠진다 — 지웠는데 숫자가 그대로면 지운 의미가 없다")
    void deletedTaggingIsExcludedFromStudyTime() {
        kioskTag(AttendanceEventType.CHECK_IN, 9, 0);
        // 사람이 하원을 잘못 넣었다 (10:00) — 순공이 1시간으로 잡힌다
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_OUT, LocalTime.of(10, 0), "하원 누락");
        em.flush();
        em.clear();
        assertThat(confirmed().getStudyMinutes()).isEqualTo(60);

        Long wrongId = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day)
                .stream()
                .filter(l -> l.getSource() == AttendanceSource.MANUAL)
                .findFirst().orElseThrow().getId();

        correctionService.deleteTagging(admin, minji.getId(), wrongId, "시각을 잘못 넣었다");
        em.flush();
        em.clear();

        // ★★ null 로 돌아간다 — 「언제 갔는지 모른다」다.
        //    하원을 지웠으니 등원만 남고, 그때는 서버가 추정하지 않는다(10/13 피드백 4-2).
        //    13시간 같은 숫자가 찍히면 그게 버그다 — 지운 뒤 더 큰 값이 나오면 안 된다
        assertThat(confirmed().getStudyMinutes()).isNull();
        // 원장에서도 빠진다
        assertThat(taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day))
                .extracting(AttendanceTaggingLog::getEventType)
                .containsExactly(AttendanceEventType.CHECK_IN);
    }

    @Test
    @DisplayName("★★ 키오스크 태깅은 지울 수 없다 — 그 학생이 정말 왔었는지 확인할 방법이 없어진다")
    void kioskTaggingCannotBeDeleted() {
        kioskTag(AttendanceEventType.CHECK_IN, 9, 0);
        Long kioskId = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day)
                .get(0).getId();

        assertThatThrownBy(() ->
                correctionService.deleteTagging(admin, minji.getId(), kioskId, "지우고 싶다"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("키오스크");
    }

    @Test
    @DisplayName("★ 조퇴를 지우면 하루가 다시 열린다 — 조퇴 취소 경로다")
    void deletingEarlyLeaveReopensTheDay() {
        kioskTag(AttendanceEventType.CHECK_IN, 9, 0);
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.EARLY_LEAVE, LocalTime.of(10, 0), "조퇴");
        em.flush();
        em.clear();
        assertThat(confirmed().getStudyMinutes()).isEqualTo(60);

        Long earlyLeaveId = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day)
                .stream()
                .filter(l -> l.getEventType() == AttendanceEventType.EARLY_LEAVE)
                .findFirst().orElseThrow().getId();

        correctionService.deleteTagging(admin, minji.getId(), earlyLeaveId, "복귀해서 조퇴 취소");
        em.flush();
        em.clear();

        // ★ 조퇴가 없어지면 등원만 남아 「모른다」로 돌아간다.
        //   조퇴를 취소한 학생은 하원을 다시 넣어야 순공이 계산된다 — 그게 정직한 표현이다
        assertThat(confirmed().getStudyMinutes()).isNull();
    }

    @Test
    @DisplayName("★ 두 번 눌러도 안전하다")
    void deleteIsIdempotent() {
        kioskTag(AttendanceEventType.CHECK_IN, 9, 0);
        correctionService.addTagging(admin, minji.getId(), day,
                AttendanceEventType.CHECK_OUT, LocalTime.of(10, 0), "하원 누락");
        em.flush();
        Long id = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(minji.getId(), day)
                .stream().filter(l -> l.getSource() == AttendanceSource.MANUAL)
                .findFirst().orElseThrow().getId();

        correctionService.deleteTagging(admin, minji.getId(), id, "오입력");
        correctionService.deleteTagging(admin, minji.getId(), id, "오입력");
        em.flush();
    }

    @Test
    @DisplayName("★ 다른 학생의 태깅 id 로는 지워지지 않는다 — 지점 권한만 보면 통과한다")
    void cannotDeleteAnotherStudentsTagging() {
        Student other = new Student("DL-2", "박서준", "010-1111-1111");
        em.persist(other);
        StudentEnrollment seojun = new StudentEnrollment(other, bundang, (short) 2026,
                "2026-0002", null, GradeType.HIGH3);
        em.persist(seojun);
        em.persist(new AttendanceTaggingLog(bundang, seojun, AttendanceEventType.CHECK_IN,
                AttendanceSource.MANUAL,
                day.atTime(9, 0).atZone(clock.getZone()).toInstant(), day));
        em.flush();

        Long othersId = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(seojun.getId(), day)
                .get(0).getId();

        assertThatThrownBy(() ->
                correctionService.deleteTagging(admin, minji.getId(), othersId, "실수"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("그 학생의 태깅");
    }
}
