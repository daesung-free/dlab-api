package com.dlab.api.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.plan.entity.LearningPlan;
import com.dlab.domain.plan.entity.LearningPlanItem;
import com.dlab.domain.plan.entity.LearningPlanOption;
import com.dlab.domain.plan.entity.LearningPlanOptionType;
import com.dlab.domain.plan.service.LearningPlanService;
import com.dlab.domain.plan.service.LearningPlanService.ItemCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
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
 * 주·일 학습계획 (F-4.11 / 앱 A-12) — 순번 기반.
 *
 * <p>0803 답변서에서 교시×요일 그리드가 폐기되고 열품타 방식(시작시각 + 소요시간 자유 입력)이
 * 됐다. 순번은 서버가 시각 순으로 매기고, 이행은 O/X 2단계다.
 */
@SpringBootTest
@Transactional
class LearningPlanTest {

    @Autowired LearningPlanService planService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    LearningPlanOption korean;
    LearningPlanOption math;
    LearningPlanOption selfStudy;
    LearningPlanOption lecture;
    LocalDate monday;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);

        korean = option(LearningPlanOptionType.SUBJECT, "국어", 1);
        math = option(LearningPlanOptionType.SUBJECT, "수학", 2);
        selfStudy = option(LearningPlanOptionType.STUDY_TYPE, "자습", 1);
        lecture = option(LearningPlanOptionType.STUDY_TYPE, "인강", 2);
        em.flush();

        monday = LocalDate.now(clock).with(DayOfWeek.MONDAY);
    }

    private LearningPlanOption option(LearningPlanOptionType type, String label, int order) {
        LearningPlanOption o = new LearningPlanOption(bundang, (short) 2026, type, label,
                (short) order);
        em.persist(o);
        return o;
    }

    private ItemCommand cmd(int hour, int minutes, LearningPlanOption subject,
                            LearningPlanOption type) {
        return new ItemCommand(LocalTime.of(hour, 0), (short) minutes,
                subject.getId(), type.getId(), null);
    }

    // ── 순번 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 순번은 학생이 보낸 순서가 아니라 시작시각 순으로 서버가 매긴다")
    void sequenceFollowsStartTime() {
        // 오후 → 오전 순서로 거꾸로 보낸다
        LearningPlan plan = planService.saveDay(minji, monday, List.of(
                cmd(14, 60, math, selfStudy),
                cmd(9, 90, korean, lecture)));

        List<LearningPlanItem> items = plan.activeItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getSequence()).isEqualTo((short) 1);
        assertThat(items.get(0).getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(items.get(1).getSequence()).isEqualTo((short) 2);
        assertThat(items.get(1).getStartTime()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("교시 개념이 없다 — 고정 시간대에 맞추지 않고 자유 시각·소요시간을 그대로 받는다")
    void freeFormTimes() {
        LearningPlan plan = planService.saveDay(minji, monday, List.of(
                new ItemCommand(LocalTime.of(9, 17), (short) 25,
                        korean.getId(), selfStudy.getId(), "수능특강 3강")));

        LearningPlanItem item = plan.activeItems().get(0);
        assertThat(item.getStartTime()).isEqualTo(LocalTime.of(9, 17));
        assertThat(item.getDurationMinutes()).isEqualTo((short) 25);
        assertThat(item.endTime()).isEqualTo(LocalTime.of(9, 42));
        assertThat(item.getMaterial()).isEqualTo("수능특강 3강");
    }

    @Test
    @DisplayName("★ 시간이 겹치면 거절한다 — 허용하면 과목별 누적 시간이 부풀어 통계가 틀어진다")
    void rejectsOverlap() {
        assertThatThrownBy(() -> planService.saveDay(minji, monday, List.of(
                cmd(9, 120, korean, selfStudy),
                cmd(10, 60, math, selfStudy))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("겹치는");
    }

    @Test
    @DisplayName("끝시각과 다음 시작시각이 같은 것은 겹침이 아니다")
    void backToBackIsFine() {
        LearningPlan plan = planService.saveDay(minji, monday, List.of(
                cmd(9, 60, korean, selfStudy),
                cmd(10, 60, math, selfStudy)));

        assertThat(plan.activeItems()).hasSize(2);
    }

    // ── 이행 O/X ──────────────────────────────────────────────

    @Test
    @DisplayName("★ 이행은 O/X 2단계이고 되돌릴 수 있다 — 못 되돌리면 오타가 통계에 영구히 남는다")
    void markAndUnmark() {
        LearningPlan plan = planService.saveDay(minji, monday,
                List.of(cmd(9, 60, korean, selfStudy)));
        Long itemId = plan.activeItems().get(0).getId();

        assertThat(planService.mark(minji, monday, itemId, true).isDone()).isTrue();
        assertThat(planService.mark(minji, monday, itemId, true).getDoneAt()).isNotNull();

        LearningPlanItem reverted = planService.mark(minji, monday, itemId, false);
        assertThat(reverted.isDone()).isFalse();
        assertThat(reverted.getDoneAt()).isNull();
    }

    @Test
    @DisplayName("★ 하루치를 다시 저장해도 같은 줄의 이행 체크는 살아남는다")
    void keepsDoneOnResave() {
        LearningPlan plan = planService.saveDay(minji, monday,
                List.of(cmd(9, 60, korean, selfStudy)));
        planService.mark(minji, monday, plan.activeItems().get(0).getId(), true);

        // 오후 계획을 하나 추가한다. 오전 체크가 풀리면 안 된다
        LearningPlan resaved = planService.saveDay(minji, monday, List.of(
                cmd(9, 60, korean, selfStudy),
                cmd(14, 60, math, lecture)));

        assertThat(resaved.activeItems().get(0).isDone()).isTrue();
        assertThat(resaved.activeItems().get(1).isDone()).isFalse();
    }

    @Test
    @DisplayName("남의 계획 항목은 체크할 수 없다")
    void cannotMarkOthersItem() {
        Student other = new Student("DL-2026-0500", "박서준", "010-3333-4444");
        em.persist(other);
        StudentEnrollment seojun = new StudentEnrollment(other, bundang, (short) 2026,
                "2026-0002", null, GradeType.HIGH3);
        em.persist(seojun);

        LearningPlan mine = planService.saveDay(minji, monday,
                List.of(cmd(9, 60, korean, selfStudy)));
        Long itemId = mine.activeItems().get(0).getId();

        assertThatThrownBy(() -> planService.mark(seojun, monday, itemId, true))
                .isInstanceOf(BusinessException.class);
    }

    // ── 지난 주 복사 ──────────────────────────────────────────

    @Test
    @DisplayName("★ 지난 주 복사는 이행 체크를 가져오지 않는다 — 가져오면 통계가 통째로 틀어진다")
    void copyDoesNotCarryDone() {
        LocalDate lastMonday = monday.minusWeeks(1);
        LearningPlan last = planService.saveDay(minji, lastMonday,
                List.of(cmd(9, 60, korean, selfStudy)));
        planService.mark(minji, lastMonday, last.activeItems().get(0).getId(), true);

        List<LocalDate> filled = planService.copyPreviousWeek(minji, monday);

        assertThat(filled).containsExactly(monday);
        LearningPlan copied = planService.findDay(minji.getId(), monday);
        assertThat(copied.activeItems()).hasSize(1);
        assertThat(copied.activeItems().get(0).isDone()).isFalse();
        assertThat(copied.getCopiedFromId()).isEqualTo(last.getId());
    }

    @Test
    @DisplayName("★ 이미 짜 둔 날은 복사가 건너뛴다 — 덮으면 먼저 입력한 것이 조용히 사라진다")
    void copySkipsExistingDays() {
        LocalDate lastMonday = monday.minusWeeks(1);
        LocalDate lastTuesday = lastMonday.plusDays(1);
        planService.saveDay(minji, lastMonday, List.of(cmd(9, 60, korean, selfStudy)));
        planService.saveDay(minji, lastTuesday, List.of(cmd(9, 60, math, selfStudy)));

        // 이번 주 월요일은 이미 직접 짰다
        planService.saveDay(minji, monday, List.of(cmd(20, 30, math, lecture)));

        List<LocalDate> filled = planService.copyPreviousWeek(minji, monday);

        assertThat(filled).containsExactly(monday.plusDays(1));
        LearningPlan kept = planService.findDay(minji.getId(), monday);
        assertThat(kept.activeItems()).hasSize(1);
        assertThat(kept.activeItems().get(0).getStartTime()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("지난 주에 아무것도 없으면 복사할 것이 없다고 알린다")
    void copyWithoutSourceFails() {
        assertThatThrownBy(() -> planService.copyPreviousWeek(minji, monday))
                .isInstanceOf(BusinessException.class);
    }

    // ── 통계 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 이행 시간은 O 체크된 계획의 소요시간 합이다 — 순공시간(출결)과 다른 값이다")
    void statisticsCountOnlyCheckedItems() {
        LearningPlan plan = planService.saveDay(minji, monday, List.of(
                cmd(9, 60, korean, selfStudy),
                cmd(14, 120, math, lecture)));
        planService.mark(minji, monday, plan.activeItems().get(0).getId(), true);

        LearningPlanService.Statistics stats =
                planService.statistics(minji.getId(), monday, monday.plusDays(6));

        assertThat(stats.plannedMinutes()).isEqualTo(180);
        assertThat(stats.doneMinutes()).isEqualTo(60);
        assertThat(stats.totalItems()).isEqualTo(2);
        assertThat(stats.doneItems()).isEqualTo(1);

        assertThat(stats.bySubject())
                .extracting(LearningPlanService.Composition::label,
                        LearningPlanService.Composition::plannedMinutes)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("국어", 60),
                        org.assertj.core.api.Assertions.tuple("수학", 120));
        assertThat(stats.byStudyType())
                .extracting(LearningPlanService.Composition::label,
                        LearningPlanService.Composition::doneMinutes)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("자습", 60),
                        org.assertj.core.api.Assertions.tuple("인강", 0));
    }

    // ── 옵션 검증 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 학습형태 ID를 과목 자리에 넣으면 거절한다 — 통과하면 통계 축이 뒤섞인다")
    void rejectsWrongOptionType() {
        assertThatThrownBy(() -> planService.saveDay(minji, monday, List.of(
                new ItemCommand(LocalTime.of(9, 0), (short) 60,
                        selfStudy.getId(), korean.getId(), null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 지점 과목은 쓸 수 없다")
    void rejectsOtherAcademyOption() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        LearningPlanOption otherKorean = new LearningPlanOption(ilsan, (short) 2026,
                LearningPlanOptionType.SUBJECT, "국어", (short) 1);
        em.persist(otherKorean);
        em.flush();

        assertThatThrownBy(() -> planService.saveDay(minji, monday, List.of(
                new ItemCommand(LocalTime.of(9, 0), (short) 60,
                        otherKorean.getId(), selfStudy.getId(), null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 과목 삭제는 soft delete다 — 물리 삭제하면 과거 통계에서 그 과목이 사라진다")
    void deleteOptionKeepsPastStatistics() {
        planService.saveDay(minji, monday, List.of(cmd(9, 60, korean, selfStudy)));
        planService.deleteOption(bundang.getId(), korean.getId());
        em.flush();

        assertThat(planService.options(bundang.getId(), (short) 2026,
                LearningPlanOptionType.SUBJECT)).extracting(LearningPlanOption::getLabel)
                .containsExactly("수학");

        assertThat(planService.statistics(minji.getId(), monday, monday).bySubject())
                .extracting(LearningPlanService.Composition::label)
                .containsExactly("국어");
    }

    // ── 주 경계 ───────────────────────────────────────────────

    @Test
    @DisplayName("★ 일요일이 들어와도 그 주의 월요일로 맞춘다 — 주 시작이 갈리면 같은 주가 두 벌로 보인다")
    void weekStartsOnMonday() {
        assertThat(LearningPlanService.weekStart(monday.plusDays(6))).isEqualTo(monday);
        assertThat(LearningPlanService.weekStart(monday)).isEqualTo(monday);
    }

    @Test
    @DisplayName("주말도 대상이다 — 이 학원은 토요일에도 운영한다")
    void saturdayIsAllowed() {
        LocalDate saturday = monday.plusDays(5);
        LearningPlan plan = planService.saveDay(minji, saturday,
                List.of(cmd(10, 180, math, selfStudy)));

        assertThat(plan.activeItems()).hasSize(1);
        assertThat(planService.findWeek(minji.getId(), monday))
                .extracting(LearningPlan::getPlanDate).contains(saturday);
    }
}
