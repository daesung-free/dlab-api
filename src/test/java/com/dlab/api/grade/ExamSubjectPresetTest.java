package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamPurpose;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.entity.ExamSubjectPreset;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.grade.service.ExamFormAdminService.SubjectInput;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 학년별 기본 과목 구성 · 연도 롤오버.
 *
 * <p>지키려는 것 — <b>2026 시드가 연구소 구성대로일 것</b>, <b>디랩 시험은 과목을 비워도
 * 만들어질 것</b>, <b>롤오버가 두 번 불려도 같을 것</b>, <b>디랩 시험은 복사하지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class ExamSubjectPresetTest {

    @Autowired ExamFormAdminService adminService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    AuthPrincipal head;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        head = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN), true, false);
        branchAdmin = new AuthPrincipal(2L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
    }

    private List<String> names(List<ExamSubjectPreset> presets) {
        return presets.stream().map(ExamSubjectPreset::getSubjectName).toList();
    }

    @Test
    @DisplayName("★ 2026 시드 — 고2는 통합사회·통합과학, 고3·N수는 탐구1·탐구2 (연구소 0921)")
    void seed2026FollowsInstitute() {
        assertThat(names(adminService.presets(head, (short) 2026, GradeType.HIGH2, null)))
                .containsExactly("국어", "수학", "영어", "한국사", "통합사회", "통합과학");
        assertThat(names(adminService.presets(head, (short) 2026, GradeType.N_SU, null)))
                .containsExactly("국어", "수학", "영어", "한국사", "탐구1", "탐구2");
    }

    @Test
    @DisplayName("★ 영어·한국사는 절대평가 — 표준점수·백분위 칸이 없다")
    void absoluteSubjectsHaveNoStandardScore() {
        ExamSubjectPreset english = adminService.presets(head, (short) 2026, GradeType.HIGH3, null)
                .stream().filter(p -> p.getSubjectCode().equals("ENGLISH")).findFirst().orElseThrow();

        assertThat(english.isHasStandardScore()).isFalse();
        assertThat(english.isHasPercentile()).isFalse();
        assertThat(english.isHasGradeLevel()).isTrue();
        assertThat(english.isHasRawScore()).isTrue();
    }

    @Test
    @DisplayName("★ 디랩 시험은 과목을 비우면 기본 구성으로 만들어진다 — 매달 6과목을 손으로 넣지 않는다")
    void academyExamFilledFromPreset() {
        ExamMaster exam = adminService.create(head, new ExamFormAdminService.Command(
                bundang.getId(), (short) 2026, GradeType.HIGH2, ExamCode.MONTHLY, "9월 더프", 1,
                List.of(), ExamPurpose.ACADEMY, LocalDate.of(2026, 9, 17)));

        assertThat(exam.activeSubjects()).extracting(ExamSubject::getSubjectName)
                .containsExactly("국어", "수학", "영어", "한국사", "통합사회", "통합과학");
        assertThat(exam.activeSubjects()).allMatch(ExamSubject::isHasRawScore);
    }

    @Test
    @DisplayName("입학 전 성적 양식은 과목을 비우면 여전히 막힌다 — 기본 구성은 디랩 시험용이다")
    void admissionFormStillRequiresSubjects() {
        assertThatThrownBy(() -> adminService.create(head, new ExamFormAdminService.Command(
                null, (short) 2096, GradeType.HIGH2, ExamCode.JUNE, "6월", 1,
                List.of(), ExamPurpose.ADMISSION, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("지점 구성을 넣으면 그 지점은 그것만 쓴다 — 공통본과 섞이지 않는다")
    void branchPresetOverridesCommon() {
        adminService.replacePresets(branchAdmin, bundang.getId(), (short) 2026, GradeType.N_SU,
                List.of(new SubjectInput("KOREAN", "국어", 1, true, true, true, true),
                        new SubjectInput("MATH", "수학", 2, true, true, true, true)));
        em.flush();

        assertThat(names(adminService.presets(branchAdmin, (short) 2026, GradeType.N_SU, bundang.getId())))
                .containsExactly("국어", "수학");
        assertThat(names(adminService.presets(head, (short) 2026, GradeType.N_SU, null)))
                .hasSize(6);
    }

    @Test
    @DisplayName("★ 같은 과목 코드로 다시 넣어도 된다 — 지운 행과 새 행이 한 flush 에서 부딪히지 않는다")
    void replaceTwiceWithSameCodes() {
        List<SubjectInput> subjects = List.of(new SubjectInput("KOREAN", "국어", 1, true, true, true, true));
        adminService.replacePresets(head, null, (short) 2097, GradeType.HIGH2, subjects);
        adminService.replacePresets(head, null, (short) 2097, GradeType.HIGH2, subjects);
        em.flush();

        assertThat(adminService.presets(head, (short) 2097, GradeType.HIGH2, null)).hasSize(1);
    }

    @Test
    @DisplayName("지점 관리자는 공통 구성을 바꿀 수 없다")
    void branchCannotEditCommon() {
        assertThatThrownBy(() -> adminService.replacePresets(branchAdmin, null, (short) 2026,
                GradeType.HIGH2, List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 롤오버 — 입학 양식과 기본 구성을 복사하고 이름의 연도를 올린다")
    void rolloverCopiesFormsAndPresets() {
        var result = adminService.rollover(head, null, (short) 2026, (short) 2027);
        em.flush();

        assertThat(result.formsCreated()).isEqualTo(9);
        assertThat(result.presetsCreated()).isEqualTo(18);
        assertThat(adminService.list(head, (short) 2027, null))
                .extracting(ExamMaster::getExamName)
                .contains("2027년 6월 학력평가", "2027학년도 수능", "6월 평가원 모의고사");
        assertThat(names(adminService.presets(head, (short) 2027, GradeType.HIGH2, null)))
                .containsExactly("국어", "수학", "영어", "한국사", "통합사회", "통합과학");
    }

    @Test
    @DisplayName("★ 두 번 불러도 같다 — 이미 있는 것은 건너뛴다")
    void rolloverIsIdempotent() {
        adminService.rollover(head, null, (short) 2026, (short) 2027);
        em.flush();
        var second = adminService.rollover(head, null, (short) 2026, (short) 2027);
        em.flush();

        assertThat(second.formsCreated()).isZero();
        assertThat(second.formsSkipped()).isEqualTo(9);
        assertThat(second.presetsCreated()).isZero();
        assertThat(second.presetsSkippedGrades())
                .containsExactly(GradeType.HIGH2, GradeType.HIGH3, GradeType.N_SU);
        assertThat(adminService.list(head, (short) 2027, null)).hasSize(9);
    }

    @Test
    @DisplayName("★ 디랩 시험 회차는 복사하지 않는다 — 시행일이 붙은 한 번뿐인 시험이다")
    void rolloverSkipsAcademyExams() {
        adminService.create(head, new ExamFormAdminService.Command(null, (short) 2026,
                GradeType.HIGH2, ExamCode.MONTHLY, "9월 더프", 1, List.of(),
                ExamPurpose.ACADEMY, LocalDate.of(2026, 9, 17)));
        em.flush();

        adminService.rollover(head, null, (short) 2026, (short) 2027);
        em.flush();

        assertThat(adminService.list(head, (short) 2027, null))
                .noneMatch(ExamMaster::isAcademyExam);
    }

    @Test
    @DisplayName("이름 속 연도만 올린다 — 연도가 없는 이름은 그대로")
    void shiftsYearsInName() {
        assertThat(ExamFormAdminService.shiftYears("2026년 6월 학력평가", 1)).isEqualTo("2027년 6월 학력평가");
        assertThat(ExamFormAdminService.shiftYears("2026학년도 수능", 2)).isEqualTo("2028학년도 수능");
        assertThat(ExamFormAdminService.shiftYears("9월 평가원 모의고사", 1)).isEqualTo("9월 평가원 모의고사");
    }

    @Test
    @DisplayName("★ 회차 목록에 문항 정보 수가 실린다 — 없으면 0, 정오표 업로드를 막는 근거다")
    void formListShowsItemCount() {
        ExamMaster withItems = adminService.create(head, new ExamFormAdminService.Command(
                null, (short) 2026, GradeType.HIGH3, ExamCode.MONTHLY, "8월 더프", 1,
                List.of(), ExamPurpose.ACADEMY, LocalDate.of(2026, 8, 18)));
        ExamMaster empty = adminService.create(head, new ExamFormAdminService.Command(
                null, (short) 2026, GradeType.HIGH3, ExamCode.MONTHLY, "9월 더프", 2,
                List.of(), ExamPurpose.ACADEMY, LocalDate.of(2026, 9, 17)));
        em.persist(new com.dlab.domain.grade.entity.ExamItem(withItems, "1", "국어", (short) 1,
                (short) 3, (short) 2, false, null, null, null, null));
        em.persist(new com.dlab.domain.grade.entity.ExamItem(withItems, "1", "국어", (short) 2,
                (short) 1, (short) 2, false, null, null, null, null));
        em.flush();

        var stats = adminService.itemStats(List.of(withItems.getId(), empty.getId()));

        assertThat(stats.get(withItems.getId()).count()).isEqualTo(2);
        assertThat(stats.get(withItems.getId()).uploadedAt()).isNotNull();
        assertThat(stats).doesNotContainKey(empty.getId());
        assertThat(com.dlab.api.admin.grade.ExamFormRequests.FormView.from(empty, stats.get(empty.getId()))
                .itemCount()).isZero();
    }
}
