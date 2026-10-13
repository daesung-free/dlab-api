package com.dlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.privacy.Masking;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.report.service.DailyReportBoardService;
import com.dlab.domain.report.service.DailyReportBoardService.BoardRow;
import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.report.entity.RankingPeriod;
import com.dlab.domain.report.service.DailyReportService;
import com.dlab.domain.report.service.StudyTimeRankingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily Report 집계 (F-4.11-6, 앱 A-3).
 *
 * <p>진도·온라인 질의응답·FCM 요약 푸시는 범위 밖이다(각각 I-23 보류 · F-4.11-7 미구현 ·
 * E-7 대기).
 */
@SpringBootTest
@Transactional
class DailyReportTest {

    @Autowired DailyReportService reportService;
    @Autowired StudyTimeRankingService rankingService;
    @Autowired DailyReportBoardService boardService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;    // 분당 · 880분
    StudentEnrollment seojun;   // 분당 · 880분 (민지와 동점)
    StudentEnrollment doyun;    // 분당 · 300분
    StudentEnrollment jiwoo;    // 일산 · 1000분
    LocalDate day1;
    AuthPrincipal admin;     // 분당 지점 관리자 — 원본 열람 가능
    AuthPrincipal staff;     // 행정 — 담임 범위를 받지 않되 이름은 가려진다

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        minji = enroll(bundang, "20260001", "김민지");
        seojun = enroll(bundang, "20260002", "이서준");
        doyun = enroll(bundang, "20260003", "최도윤");
        jiwoo = enroll(ilsan, "20260101", "박지우");

        day1 = LocalDate.of(2026, 8, 3);        // 월요일

        daily(minji, day1, DailyStatus.PRESENT, 480);
        daily(minji, day1.plusDays(1), DailyStatus.LATE, 400);
        daily(seojun, day1, DailyStatus.PRESENT, 880);
        daily(doyun, day1, DailyStatus.PRESENT, 300);
        daily(doyun, day1.plusDays(1), DailyStatus.ABSENT, null);
        daily(jiwoo, day1, DailyStatus.PRESENT, 1000);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        staff = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.STAFF), false);
    }

    private StudentEnrollment enroll(Academy academy, String studentNo, String name) {
        Student student = new Student("DL-" + studentNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, studentNo, null, GradeType.HIGH3);
        em.persist(enrollment);
        return enrollment;
    }

    private void daily(StudentEnrollment enrollment, LocalDate date,
                       DailyStatus status, Integer studyMinutes) {
        AttendanceDailyStatus row = new AttendanceDailyStatus(
                enrollment.getAcademy(), enrollment, date, status);
        if (studyMinutes != null) {
            row.recordStudyMinutes(studyMinutes, Instant.now());
        }
        em.persist(row);
    }

    // ── 랭킹 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 전체 1등과 지점 1등이 다르다 — 지점 랭킹을 따로 매기지 않으면 둘 다 못 보여준다")
    void overallAndAcademyTopDiffer() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);

        assertThat(view.overallTop().studentName()).isEqualTo("박지우");   // 일산 1000분
        assertThat(view.academyTop().studyMinutes()).isEqualTo(880);       // 분당 1등
    }

    @Test
    @DisplayName("★★ 동점은 같은 등수다 — 임의로 순서를 매기면 같은 시간을 공부한 둘에게 다른 등수가 보인다")
    void tiedStudentsShareRank() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var minjiView = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);
        var seojunView = rankingService.view(reload(seojun), RankingPeriod.WEEKLY, day1);

        assertThat(minjiView.myAcademy().ranking()).isEqualTo(1);
        assertThat(seojunView.myAcademy().ranking()).isEqualTo(1);

        // 동점 다음은 2등이 아니라 3등이다
        var doyunView = rankingService.view(reload(doyun), RankingPeriod.WEEKLY, day1);
        assertThat(doyunView.myAcademy().ranking()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 다시 돌려도 등수가 중복되지 않는다 — 출결이 뒤늦게 정정되면 실제로 다시 돌린다")
    void rebuildIsIdempotent() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);

        assertThat(view.academySize()).isEqualTo(3);    // 분당 3명. 두 배로 늘지 않는다
    }

    @Test
    @DisplayName("★ 순공 기록이 없는 학생은 랭킹에 없다 — 0분으로 채우면 꼬리가 재원생 명단이 된다")
    void studentWithoutStudyTimeIsAbsentFromRanking() {
        StudentEnrollment newbie = enroll(bundang, "20260004", "정하늘");
        em.flush();

        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(newbie), RankingPeriod.WEEKLY, day1);

        assertThat(view.myAcademy()).isNull();
        assertThat(view.academySize()).isEqualTo(3);
    }

    @Test
    @DisplayName("주간은 그 주 전체를 합산한다 — 일간은 그날만")
    void weeklySumsTheWholeWeek() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        rankingService.rebuild(RankingPeriod.DAILY, day1);
        em.flush();
        em.clear();

        assertThat(rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1)
                .myAcademy().studyMinutes()).isEqualTo(880);
        assertThat(rankingService.view(reload(minji), RankingPeriod.DAILY, day1)
                .myAcademy().studyMinutes()).isEqualTo(480);
    }

    @Test
    @DisplayName("배치 전이면 비어 있다 — 그 자리에서 집계하지 않는다")
    void emptyBeforeBatch() {
        var view = rankingService.view(minji, RankingPeriod.WEEKLY, day1);

        assertThat(view.overallTop()).isNull();
        assertThat(view.myAcademy()).isNull();
    }

    // ── 달력·일자 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 확정 전인 날은 상태·순공이 null이다 — 0으로 채우면 결석처럼 보인다")
    void unconfirmedDayIsNull() {
        var report = reportService.day(minji, day1.plusDays(5));

        assertThat(report.finalStatus()).isNull();
        assertThat(report.studyMinutes()).isNull();
    }

    @Test
    @DisplayName("달력은 월간 합계·출석률을 함께 내린다 — 지각은 출석으로 센다")
    void monthlyCarriesTotals() {
        var month = reportService.month(minji, 2026, 8);

        assertThat(month.studyMinutes()).isEqualTo(880);
        assertThat(month.attendanceRate()).isEqualTo(100);      // 등원 + 지각
        assertThat(month.days()).hasSize(2);
    }

    @Test
    @DisplayName("결석일은 출석률 분모에 들어간다")
    void absentDayLowersRate() {
        var month = reportService.month(doyun, 2026, 8);

        assertThat(month.attendanceRate()).isEqualTo(50);
    }

    // ── 셀프 피드백 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 하루 1행이다 — 다시 쓰면 덮어쓴다")
    void feedbackIsOverwritten() {
        LocalDate today = LocalDate.now(clock);
        reportService.writeFeedback(minji, today, "오늘은 집중이 잘 됐다");
        reportService.writeFeedback(minji, today, "다시 생각해보니 아쉬웠다");
        em.flush();

        assertThat(reportService.day(minji, today).selfFeedback())
                .isEqualTo("다시 생각해보니 아쉬웠다");
    }

    @Test
    @DisplayName("★ 미래 날짜에는 못 쓴다 — 달력이 오지 않은 칸을 채운 것으로 표시한다")
    void futureFeedbackIsRejected() {
        assertThatThrownBy(() -> reportService.writeFeedback(
                minji, LocalDate.now(clock).plusDays(1), "내일 잘하겠다"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("미래");
    }

    @Test
    @DisplayName("달력이 피드백 작성 여부를 함께 내린다 — 앱이 날짜마다 다시 묻지 않게")
    void calendarMarksFeedback() {
        reportService.writeFeedback(minji, day1, "첫날 회고");
        em.flush();

        var month = reportService.month(minji, 2026, 8);

        assertThat(month.days()).anySatisfy(cell -> {
            assertThat(cell.date()).isEqualTo(day1);
            assertThat(cell.hasFeedback()).isTrue();
        });
    }


    // ── 관리자 목록·순위 (F-4.11-6) ────────────────────────────
    //
    // 화면은 전교생을 줄 세운다. 학생별 API 를 학생 수만큼 부르면 200명짜리 지점에서
    // 쿼리가 200번이라 목록을 서버가 한 번에 만든다. 여기서 고정하는 것은 넷이다 —
    // 순위를 서버가 전체 기준으로 세는가, 동점 규칙이 앱 랭킹과 같은가,
    // 하원 미입력이 0으로 섞이지 않는가, 쿼리가 학생 수에 비례하지 않는가.

    @Test
    @DisplayName("★ 전교생이 한 번에 나오고 등수도 서버가 매긴다 — 화면이 학생마다 부르지 않게")
    void boardRanksEveryStudentInOneCall() {
        periods();
        attend(minji, day1);
        attend(seojun, day1);
        attend(doyun, day1);

        List<BoardRow> rows = board(admin, day1, day1).getContent();

        // 기본 정렬은 순공 많은 순 — 등수 1등이 첫 줄이다
        assertThat(rows).extracting(BoardRow::studentNo)
                .containsExactly("20260002", "20260001", "20260003");
        assertThat(rows).extracting(BoardRow::studyMinutes)
                .containsExactly(880, 480, 300);
        assertThat(rows).extracting(BoardRow::studyRank)
                .containsExactly(1, 2, 3);
        // 다른 지점 학생은 섞이지 않는다
        assertThat(rows).extracting(BoardRow::enrollmentId).doesNotContain(jiwoo.getId());
    }

    @Test
    @DisplayName("★★ 동점은 같은 등수이고 다음은 건너뛴다(1,1,3) — 앱 순공 랭킹과 규칙이 갈리면 안 된다")
    void tiedStudentsShareRankInBoard() {
        periods();
        attend(minji, day1);
        attend(minji, day1.plusDays(1));    // 480 + 400 = 880
        attend(seojun, day1);               // 880
        attend(doyun, day1);                // 300

        List<BoardRow> rows = board(admin, day1, day1.plusDays(1)).getContent();

        assertThat(rowOf(rows, minji).studyMinutes()).isEqualTo(880);
        assertThat(rowOf(rows, minji).studyRank()).isEqualTo(1);
        assertThat(rowOf(rows, seojun).studyRank()).isEqualTo(1);
        // 동점 다음은 2등이 아니라 3등이다 — "N명 중 3등"의 분모와 어긋나지 않게
        assertThat(rowOf(rows, doyun).studyRank()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★ 하원 태깅이 없는 날은 집계하지 않는다 — 0으로 채우면 진짜 0분과 구분되지 않는다")
    void dayWithoutCheckOutIsNotMeasured() {
        periods();
        // 등원만 찍고 갔다. ⚠️ 확정 배치는 이 날에도 숫자를 넣어두는데(마지막 교시 종료로
        //    닫는다) 그 값을 쓰면 수기 입력 전후로 숫자가 달라진다
        tag(minji, day1, AttendanceEventType.CHECK_IN, 8, 0);
        attend(seojun, day1);

        List<BoardRow> rows = board(admin, day1, day1).getContent();
        BoardRow row = rowOf(rows, minji);

        assertThat(row.studyMinutes()).isNull();          // 0 이 아니다
        assertThat(row.presenceMinutes()).isNull();
        assertThat(row.measuredDays()).isZero();
        assertThat(row.attendedDays()).isEqualTo(1);
        assertThat(row.missingCheckOutDays()).isEqualTo(1);
        // ★ 등수도 없다. 꼴찌로 내리면 미입력이 성적처럼 읽힌다
        assertThat(row.studyRank()).isNull();
        assertThat(rowOf(rows, seojun).studyRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 집중도는 내리지 않는다 — 정의가 없는 지표를 숫자로 채우면 화면이 사실로 보여준다")
    void concentrationIsNotInvented() {
        periods();
        attend(minji, day1);

        assertThat(rowOf(board(admin, day1, day1).getContent(), minji).concentration()).isNull();
    }

    @Test
    @DisplayName("★ 재실시간은 급식을 빼지 않는다 — 순공과의 차이가 화면이 보여주려는 정보다")
    void presenceKeepsMealTimeThatStudyTimeDrops() {
        periods();
        // 확정 행이 없는 학생이라 즉석 계산된다 — 같은 재실 구간에서 두 값이 나오는지 본다
        StudentEnrollment haneul = enroll(bundang, "20260004", "정하늘");
        em.flush();
        tag(haneul, day1, AttendanceEventType.CHECK_IN, 8, 0);
        tag(haneul, day1, AttendanceEventType.CHECK_OUT, 18, 0);

        BoardRow row = rowOf(board(admin, day1, day1).getContent(), haneul);

        assertThat(row.presenceMinutes()).isEqualTo(600);   // 08:00~18:00
        assertThat(row.studyMinutes()).isEqualTo(540);      // 급식 60분이 빠진다
    }

    @Test
    @DisplayName("★ 루틴 이행은 O/X 2단계다 — 예정·배부는 분모에 넣지 않는다")
    void routineCompletionIsBinary() {
        periods();
        attend(minji, day1);

        result(routine("영단어", (short) 1), minji, day1).submit((short) 90);     // O
        result(routine("수학", (short) 2), minji, day1).markNotSubmitted();       // X
        result(routine("국어", (short) 3), minji, day1).distribute();             // 아직 결과 아님
        em.flush();

        BoardRow row = rowOf(board(admin, day1, day1).getContent(), minji);

        assertThat(row.routineDone()).isEqualTo(1);
        assertThat(row.routineMissed()).isEqualTo(1);
        assertThat(row.routineJudged()).isEqualTo(2);        // 배부분은 빠진다
        assertThat(row.routineCompletionRate()).isEqualTo(50);
    }

    @Test
    @DisplayName("루틴이 없던 기간은 이행률이 null이다 — 0%로 두면 전교생이 미이행으로 보인다")
    void routineRateIsNullWhenNothingWasJudged() {
        periods();
        attend(minji, day1);

        BoardRow row = rowOf(board(admin, day1, day1).getContent(), minji);

        assertThat(row.routineJudged()).isZero();
        assertThat(row.routineCompletionRate()).isNull();
        assertThat(row.routineRank()).isNull();
    }

    @Test
    @DisplayName("★ 등수는 반 필터·페이지와 무관하다 — 거른 뒤에 세면 담임과 본사가 다른 숫자를 본다")
    void rankIsScopedToAcademyNotToTheFilter() {
        periods();
        attend(minji, day1);
        attend(seojun, day1);
        attend(doyun, day1);

        ClassMaster class2 = new ClassMaster(bundang, (short) 2026, "2반", ClassType.FIXED, null);
        em.persist(class2);
        em.persist(new ClassAssignment(bundang, doyun, class2, ClassType.FIXED));
        em.flush();

        // 3등 학생만 담긴 반을 조회해도 1등이 되지 않는다
        List<BoardRow> filtered = boardService
                .board(admin, bundang.getId(), day1, day1, class2.getId(), PageRequest.of(0, 50))
                .getContent();
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).studyRank()).isEqualTo(3);

        // 2페이지 첫 줄도 1등이 아니다
        Page<BoardRow> second = boardService
                .board(admin, bundang.getId(), day1, day1, null, PageRequest.of(1, 1));
        assertThat(second.getTotalElements()).isEqualTo(3);
        assertThat(second.getContent().get(0).studyRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 담임은 맡은 학생만 보고 전체 건수도 그 기준이다 — 페이징 뒤에 걸러내면 어긋난다")
    void homeroomTeacherSeesOnlyOwnStudents() {
        periods();
        attend(minji, day1);
        attend(seojun, day1);

        Teacher homeroom = new Teacher(bundang, "김담임", null);
        em.persist(homeroom);
        ClassMaster myClass = new ClassMaster(bundang, (short) 2026, "1반", ClassType.FIXED, homeroom);
        em.persist(myClass);
        em.persist(new ClassAssignment(bundang, minji, myClass, ClassType.FIXED));
        Account account = Account.forTeacher(homeroom, "daily-report-homeroom", "x", false);
        em.persist(account);
        em.flush();

        AuthPrincipal teacher = AuthPrincipal.of(account.getId(), "TEACHER", bundang.getId(),
                List.of(Role.TEACHER), false);

        Page<BoardRow> page = boardService
                .board(teacher, bundang.getId(), day1, day1, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(minji.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        // 등수는 여전히 지점 전체 기준이다 — 서준(880)이 1등이므로 민지는 2등이다
        assertThat(page.getContent().get(0).studyRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 이름은 상위 관리자가 아니면 가려지고 masked 플래그가 함께 간다")
    void nameIsMaskedForNonSeniorStaff() {
        periods();
        attend(minji, day1);

        BoardRow raw = rowOf(board(admin, day1, day1).getContent(), minji);
        BoardRow masked = rowOf(board(staff, day1, day1).getContent(), minji);

        assertThat(raw.studentName()).isEqualTo("김민지");
        assertThat(raw.masked()).isFalse();
        assertThat(masked.studentName()).isEqualTo(Masking.name("김민지"));
        // 플래그가 없으면 화면이 "이름이 잘못 저장됐다"로 오인한다
        assertThat(masked.masked()).isTrue();
    }

    @Test
    @DisplayName("미집계 학생은 어느 방향으로 정렬해도 뒤로 간다 — 맨 위로 올라오면 미입력이 1등 자리에 놓인다")
    void unmeasuredStudentsSortLast() {
        periods();
        attend(minji, day1);
        tag(seojun, day1, AttendanceEventType.CHECK_IN, 8, 0);   // 하원 미입력

        // 서준은 하원 미입력, 도윤은 등원조차 없다 — 둘 다 미집계라 학번 순으로 뒤에 붙는다
        assertThat(board(admin, day1, day1).getContent())
                .extracting(BoardRow::enrollmentId)
                .containsExactly(minji.getId(), seojun.getId(), doyun.getId());

        // ★ 오름차순으로 뒤집어도 미집계가 앞으로 오지 않는다 — reversed()로 만들면
        //   nullsLast 가 같이 뒤집혀 미입력 학생이 "순공 적은 순" 1위가 된다
        List<BoardRow> ascending = boardService.board(admin, bundang.getId(), day1, day1, null,
                PageRequest.of(0, 50, Sort.by("studyMinutes"))).getContent();
        assertThat(ascending).extracting(BoardRow::enrollmentId)
                .containsExactly(minji.getId(), seojun.getId(), doyun.getId());
    }

    @Test
    @DisplayName("기간이 뒤집히거나 31일을 넘으면 거절한다 — 원장을 기간 통째로 읽는다")
    void invalidRangeIsRejected() {
        assertThatThrownBy(() -> board(admin, day1.plusDays(1), day1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> board(admin, day1, day1.plusDays(31)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("31일");
    }

    @Test
    @DisplayName("다른 지점을 요청하면 거부된다 — academyId를 그대로 믿으면 지점 격리가 뚫린다")
    void otherAcademyIsRejected() {
        assertThatThrownBy(() -> boardService.board(admin, ilsan.getId(), day1, day1, null,
                PageRequest.of(0, 50)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 쿼리 수가 학생 수에 비례하지 않는다 — 1명이든 10명이든 같다")
    void queryCountDoesNotGrowWithStudents() {
        periods();
        attend(minji, day1);
        long few = countBoardQueries();

        for (int i = 0; i < 10; i++) {
            StudentEnrollment extra = enroll(bundang, "2026020%d".formatted(i), "학생" + i);
            em.flush();
            attend(extra, day1);
        }
        long many = countBoardQueries();

        assertThat(many).isEqualTo(few);
        // 재원생 1 + 반 배정 1 + 태깅 원장 1 + 확정 출결 1 + 루틴 집계 1 + 평일 교시 1
        assertThat(many).isLessThanOrEqualTo(6);
    }

    private long countBoardQueries() {
        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        stats.clear();

        board(admin, day1, day1);
        return stats.getPrepareStatementCount();
    }

    // ── 목록 픽스처 ───────────────────────────────────────────

    /** 평일 교시 — 급식이 순공에서 빠지는지 보려고 점심을 사이에 둔다. */
    private void periods() {
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 1, "오전자습",
                DayType.WEEKDAY, PeriodType.SELF_STUDY, LocalTime.of(8, 0), LocalTime.of(12, 0)));
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 2, "점심",
                DayType.WEEKDAY, PeriodType.MEAL, LocalTime.of(12, 0), LocalTime.of(13, 0)));
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 3, "오후자습",
                DayType.WEEKDAY, PeriodType.SELF_STUDY, LocalTime.of(13, 0), LocalTime.of(22, 0)));
        em.flush();
    }

    /** 등원·하원을 함께 찍는다 — 그래야 그날이 집계 대상이 된다. */
    private void attend(StudentEnrollment enrollment, LocalDate date) {
        tag(enrollment, date, AttendanceEventType.CHECK_IN, 8, 0);
        tag(enrollment, date, AttendanceEventType.CHECK_OUT, 22, 0);
    }

    private void tag(StudentEnrollment enrollment, LocalDate date,
                     AttendanceEventType type, int hour, int minute) {
        em.persist(new AttendanceTaggingLog(enrollment.getAcademy(), enrollment, type,
                AttendanceSource.KIOSK_NFC,
                // ★ systemDefault()를 쓰면 CI·운영(UTC)에서만 9시간 어긋난다
                date.atTime(hour, minute).atZone(TimeConfig.KST).toInstant(), date));
        em.flush();
    }

    private DailyRoutine routine(String name, short sortOrder) {
        DailyRoutine routine = new DailyRoutine(bundang, (short) 2026, (short) 8, null,
                name, name, (short) 100, false, sortOrder);
        em.persist(routine);
        return routine;
    }

    private DailyRoutineResult result(DailyRoutine routine, StudentEnrollment enrollment,
                                      LocalDate date) {
        DailyRoutineResult result = new DailyRoutineResult(routine, enrollment, date);
        em.persist(result);
        return result;
    }

    private Page<BoardRow> board(AuthPrincipal who, LocalDate from, LocalDate to) {
        return boardService.board(who, bundang.getId(), from, to, null, PageRequest.of(0, 50));
    }

    private BoardRow rowOf(List<BoardRow> rows, StudentEnrollment enrollment) {
        return rows.stream().filter(r -> r.enrollmentId().equals(enrollment.getId()))
                .findFirst().orElseThrow();
    }

    private StudentEnrollment reload(StudentEnrollment enrollment) {
        return em.find(StudentEnrollment.class, enrollment.getId());
    }
}
