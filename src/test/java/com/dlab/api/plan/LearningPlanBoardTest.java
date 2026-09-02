package com.dlab.api.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.plan.entity.LearningPlan;
import com.dlab.domain.plan.entity.LearningPlanOption;
import com.dlab.domain.plan.entity.LearningPlanOptionType;
import com.dlab.domain.plan.service.LearningPlanBoardService;
import com.dlab.domain.plan.service.LearningPlanBoardService.BoardRow;
import com.dlab.domain.plan.service.LearningPlanService;
import com.dlab.domain.plan.service.LearningPlanService.ItemCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반·지점 학습계획 현황 목록 (F-4.11-2).
 *
 * <p>학생별 통계 API를 학생 수만큼 부르는 대신 목록을 서버가 한 번에 만든다.
 * 여기서 지키는 것은 셋이다 — <b>계획을 안 쓴 학생이 목록에 남는가</b>(그게 이 화면의 대상이다),
 * 이행률이 분 기준으로 맞는가, <b>쿼리가 학생 수에 비례하지 않는가</b>.
 */
@SpringBootTest
@Transactional
class LearningPlanBoardTest {

    private static final short YEAR = 2026;

    @Autowired LearningPlanBoardService boardService;
    @Autowired LearningPlanService planService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    ClassMaster class1;
    ClassMaster class2;
    LearningPlanOption korean;
    LearningPlanOption selfStudy;
    LearningPlanOption ilsanKorean;
    LearningPlanOption ilsanSelfStudy;
    LocalDate monday;
    LocalDate sunday;
    int studentSeq;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        class1 = new ClassMaster(bundang, YEAR, "1반", ClassType.FIXED, null);
        class2 = new ClassMaster(bundang, YEAR, "2반", ClassType.FIXED, null);
        em.persist(class1);
        em.persist(class2);

        korean = option(bundang, LearningPlanOptionType.SUBJECT, "국어");
        selfStudy = option(bundang, LearningPlanOptionType.STUDY_TYPE, "자습");
        ilsanKorean = option(ilsan, LearningPlanOptionType.SUBJECT, "국어");
        ilsanSelfStudy = option(ilsan, LearningPlanOptionType.STUDY_TYPE, "자습");

        monday = LocalDate.now(clock).with(DayOfWeek.MONDAY);
        sunday = monday.plusDays(6);
        em.flush();
    }

    // ── 목록 구성 ─────────────────────────────────────────────

    @Test
    @DisplayName("★★ 계획을 한 줄도 안 쓴 학생도 목록에 남는다 — 빠지면 화면이 '미작성'을 못 그린다")
    void studentsWithoutPlansRemain() {
        StudentEnrollment wrote = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment blank = enrollment("이준호", "2026-0002", class1);
        plan(wrote, monday, 60);

        List<BoardRow> rows = board(null).getContent();

        assertThat(rows).extracting(BoardRow::enrollmentId)
                .containsExactlyInAnyOrder(wrote.getId(), blank.getId());

        BoardRow blankRow = rowOf(rows, blank);
        assertThat(blankRow.plannedMinutes()).isZero();
        assertThat(blankRow.totalItems()).isZero();
        assertThat(blankRow.plannedDays()).isZero();
        // 월~일 7일을 통째로 안 썼다
        assertThat(blankRow.missingDays()).isEqualTo(7);
        assertThat(blankRow.completionRate()).isZero();
    }

    @Test
    @DisplayName("반 이름이 함께 나오고, 반 미배정 학생도 지점 전체 조회에는 들어온다")
    void classNameAndUnassigned() {
        StudentEnrollment assigned = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment unassigned = enrollment("이준호", "2026-0002", null);

        List<BoardRow> rows = board(null).getContent();

        assertThat(rowOf(rows, assigned).className()).isEqualTo("1반");
        assertThat(rowOf(rows, assigned).classId()).isEqualTo(class1.getId());
        assertThat(rowOf(rows, unassigned).className()).isNull();
    }

    @Test
    @DisplayName("다른 지점 학생은 섞이지 않는다")
    void otherAcademyIsExcluded() {
        StudentEnrollment mine = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment other = enrollmentOf(ilsan, "박서준", "2026-0009", null);
        planService.saveDay(other, monday, List.of(new ItemCommand(LocalTime.of(9, 0),
                (short) 120, ilsanKorean.getId(), ilsanSelfStudy.getId(), null)));

        assertThat(board(null).getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(mine.getId())
                .doesNotContain(other.getId());
    }

    // ── 이행률·미작성일 ────────────────────────────────────────

    @Test
    @DisplayName("★ 이행률은 줄 수가 아니라 분 기준이다 — 짧은 항목을 여러 개 체크해도 성실해 보이지 않는다")
    void completionRateIsMinuteBased() {
        StudentEnrollment minji = enrollment("김민지", "2026-0001", class1);

        // 10분짜리 2줄은 체크, 180분짜리 1줄은 미체크 → 줄 수로는 67%, 분으로는 10%
        LearningPlan plan = planService.saveDay(minji, monday, List.of(
                item(9, 10), item(10, 10), item(13, 180)));
        planService.mark(minji, monday, plan.activeItems().get(0).getId(), true);
        planService.mark(minji, monday, plan.activeItems().get(1).getId(), true);

        BoardRow row = rowOf(board(null).getContent(), minji);
        assertThat(row.plannedMinutes()).isEqualTo(200);
        assertThat(row.doneMinutes()).isEqualTo(20);
        assertThat(row.completionRate()).isEqualTo(10);
        assertThat(row.totalItems()).isEqualTo(3);
        assertThat(row.doneItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("미작성일은 기간 일수에서 계획을 쓴 날을 뺀 값이다")
    void missingDays() {
        StudentEnrollment minji = enrollment("김민지", "2026-0001", class1);
        plan(minji, monday, 60);
        plan(minji, monday.plusDays(1), 60);

        BoardRow row = rowOf(board(null).getContent(), minji);
        assertThat(row.plannedDays()).isEqualTo(2);
        assertThat(row.missingDays()).isEqualTo(5);
    }

    @Test
    @DisplayName("계획이 0분이면 이행률은 0% — 나눌 게 없다고 100%로 두면 미작성자가 우등생으로 뜬다")
    void emptyPlanIsZeroPercent() {
        StudentEnrollment minji = enrollment("김민지", "2026-0001", class1);
        assertThat(rowOf(board(null).getContent(), minji).completionRate()).isZero();
    }

    // ── 필터·정렬 ─────────────────────────────────────────────

    @Test
    @DisplayName("반 필터를 걸면 그 반만 나온다")
    void classFilter() {
        StudentEnrollment inClass1 = enrollment("김민지", "2026-0001", class1);
        enrollment("이준호", "2026-0002", class2);
        enrollment("박서준", "2026-0003", null);

        assertThat(board(class1.getId()).getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(inClass1.getId());
    }

    @Test
    @DisplayName("★ 정렬 기본값은 이행률 낮은 순 — 손이 필요한 학생을 먼저 본다")
    void defaultSortIsWorstFirst() {
        StudentEnrollment diligent = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment lazy = enrollment("이준호", "2026-0002", class1);
        StudentEnrollment blank = enrollment("박서준", "2026-0003", class1);

        LearningPlan good = plan(diligent, monday, 60);
        planService.mark(diligent, monday, good.activeItems().get(0).getId(), true);
        plan(lazy, monday, 60);

        // lazy·blank 둘 다 0%지만 미작성일이 많은 blank가 먼저다
        assertThat(board(null).getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(blank.getId(), lazy.getId(), diligent.getId());
    }

    @Test
    @DisplayName("정렬 필드를 지정할 수 있다 — 이행률 높은 순")
    void explicitSort() {
        StudentEnrollment diligent = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment lazy = enrollment("이준호", "2026-0002", class1);
        LearningPlan good = plan(diligent, monday, 60);
        planService.mark(diligent, monday, good.activeItems().get(0).getId(), true);
        plan(lazy, monday, 60);

        Page<BoardRow> page = boardService.board(bundang.getId(), monday, sunday, null,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "completionRate")));

        assertThat(page.getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(diligent.getId(), lazy.getId());
    }

    @Test
    @DisplayName("★ 허용 목록에 없는 정렬 필드는 무시한다 — 임의 컬럼 정렬로 예외가 나면 안 된다")
    void unknownSortIsIgnored() {
        StudentEnrollment a = enrollment("김민지", "2026-0001", class1);
        StudentEnrollment b = enrollment("이준호", "2026-0002", class1);

        Page<BoardRow> page = boardService.board(bundang.getId(), monday, sunday, null,
                PageRequest.of(0, 50, Sort.by("secretColumn")));

        // 기본 정렬로 떨어지고, 동률이면 학번 순이라 순서가 결정적이다
        assertThat(page.getContent()).extracting(BoardRow::enrollmentId)
                .containsExactly(a.getId(), b.getId());
    }

    @Test
    @DisplayName("페이징이 걸린다 — 지점 전체는 수백 명이라 한 번에 다 내리지 않는다")
    void paging() {
        enrollment("김민지", "2026-0001", class1);
        enrollment("이준호", "2026-0002", class1);
        enrollment("박서준", "2026-0003", class1);

        Page<BoardRow> page = boardService.board(bundang.getId(), monday, sunday, null,
                PageRequest.of(1, 2, Sort.by("studentNo")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).studentNo()).isEqualTo("2026-0003");
    }

    @Test
    @DisplayName("기간이 뒤집히면 거절한다")
    void invalidRange() {
        assertThatThrownBy(() -> boardService.board(
                bundang.getId(), sunday, monday, null, PageRequest.of(0, 50)))
                .isInstanceOf(BusinessException.class);
    }

    // ── N+1 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 쿼리 수가 학생 수에 비례하지 않는다 — 1명이든 10명이든 같다")
    void queryCountDoesNotGrowWithStudents() {
        StudentEnrollment lone = enrollment("김민지", "2026-0001", class1);
        plan(lone, monday, 60);
        long few = countQueries();

        for (int i = 0; i < 10; i++) {
            StudentEnrollment e = enrollment("학생" + i, "2026-01%02d".formatted(i), class1);
            plan(e, monday, 60);
            plan(e, monday.plusDays(1), 90);
        }
        long many = countQueries();

        assertThat(many).isEqualTo(few);
        // 재원생 1 + 반 배정 1 + 기간 집계 1
        assertThat(many).isLessThanOrEqualTo(3);
    }

    private long countQueries() {
        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        stats.clear();

        boardService.board(bundang.getId(), monday, sunday, null, PageRequest.of(0, 50));
        return stats.getPrepareStatementCount();
    }

    // ── 픽스처 ────────────────────────────────────────────────

    private Page<BoardRow> board(Long classId) {
        return boardService.board(bundang.getId(), monday, sunday, classId, pageable());
    }

    private Pageable pageable() {
        return PageRequest.of(0, 50);
    }

    private BoardRow rowOf(List<BoardRow> rows, StudentEnrollment enrollment) {
        return rows.stream().filter(r -> r.enrollmentId().equals(enrollment.getId()))
                .findFirst().orElseThrow();
    }

    private StudentEnrollment enrollment(String name, String studentNo, ClassMaster clazz) {
        return enrollmentOf(bundang, name, studentNo, clazz);
    }

    private StudentEnrollment enrollmentOf(Academy academy, String name, String studentNo,
                                           ClassMaster clazz) {
        Student student = new Student("DL-%s-%04d".formatted(YEAR, ++studentSeq), name,
                "010-0000-%04d".formatted(studentSeq));
        em.persist(student);

        StudentEnrollment enrollment = new StudentEnrollment(student, academy, YEAR,
                studentNo, null, GradeType.HIGH3);
        em.persist(enrollment);

        if (clazz != null) {
            em.persist(new ClassAssignment(academy, enrollment, clazz, ClassType.FIXED));
        }
        em.flush();
        return enrollment;
    }

    private LearningPlanOption option(Academy academy, LearningPlanOptionType type,
                                      String label) {
        LearningPlanOption o = new LearningPlanOption(academy, YEAR, type, label, (short) 1);
        em.persist(o);
        return o;
    }

    private LearningPlan plan(StudentEnrollment enrollment, LocalDate date, int minutes) {
        return planService.saveDay(enrollment, date, List.of(item(9, minutes)));
    }

    private ItemCommand item(int hour, int minutes) {
        return new ItemCommand(LocalTime.of(hour, 0), (short) minutes,
                korean.getId(), selfStudy.getId(), null);
    }
}
