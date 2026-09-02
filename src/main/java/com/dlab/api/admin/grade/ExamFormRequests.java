package com.dlab.api.admin.grade;

import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.user.entity.GradeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 성적 양식 관리 DTO. */
public final class ExamFormRequests {

    private ExamFormRequests() {
    }

    /**
     * 시험 회차 등록.
     *
     * @param academyId  비우면 <b>전 지점 공통</b> — 본사만 만들 수 있다.
     *                   지점 행이 있으면 그 지점에서는 공통본 대신 그것이 쓰인다
     * @param examName   신상기록부에 적힌 문구 그대로. 서버가 연도를 조합해 만들지 않는다 —
     *                   수능은 응시 연도와 학년도가 어긋나(2025년 11월 = 2026학년도)
     *                   조합식이 매번 틀린다
     */
    public record ExamFormCreate(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "학년은 필수입니다.") GradeType gradeType,
            @NotNull(message = "시험 구분은 필수입니다.") ExamCode examCode,
            @NotBlank(message = "시험 이름은 필수입니다.") @Size(max = 64) String examName,
            int sortOrder,
            @NotEmpty(message = "과목이 없는 시험은 만들 수 없습니다.")
            @Valid List<ExamFormSubject> subjects) {

        public ExamFormAdminService.Command toCommand() {
            return new ExamFormAdminService.Command(academyId, year, gradeType, examCode,
                    examName, sortOrder,
                    subjects.stream()
                            .map(s -> new ExamFormAdminService.SubjectInput(
                                    s.subjectCode(), s.subjectName(), s.sortOrder(),
                                    s.hasStandardScore(), s.hasPercentile(), s.hasGradeLevel()))
                            .toList());
        }
    }

    /**
     * @param subjectCode 통계 축. 학년이 달라도 국어끼리 묶이게 하는 값이라
     *                    표시명과 분리한다 — 표시명("통합사회")은 해마다 바뀐다
     * @param hasStandardScore 한국사처럼 절대평가 과목은 {@code false}로 둘 것.
     *                         일괄로 열면 학생이 없는 점수를 지어내 채운다
     */
    public record ExamFormSubject(
            @NotBlank(message = "과목 코드는 필수입니다.") @Size(max = 20) String subjectCode,
            @NotBlank(message = "과목명은 필수입니다.") @Size(max = 30) String subjectName,
            int sortOrder,
            boolean hasStandardScore,
            boolean hasPercentile,
            boolean hasGradeLevel) {
    }

    /** 등록된 회차 한 줄. */
    public record FormView(Long examMasterId, Long academyId, short year, String gradeType,
                           String examCode, String examName, int sortOrder,
                           List<SubjectView> subjects) {

        public static FormView from(ExamMaster exam) {
            return new FormView(exam.getId(),
                    exam.isCommon() ? null : exam.getAcademy().getId(),
                    exam.getYear(), exam.getGradeType().name(), exam.getExamCode().name(),
                    exam.getExamName(), exam.getSortOrder(),
                    exam.activeSubjects().stream().map(SubjectView::from).toList());
        }
    }

    public record SubjectView(Long examSubjectId, String subjectCode, String subjectName,
                              int sortOrder, boolean hasStandardScore, boolean hasPercentile,
                              boolean hasGradeLevel) {

        static SubjectView from(ExamSubject subject) {
            return new SubjectView(subject.getId(), subject.getSubjectCode(),
                    subject.getSubjectName(), subject.getSortOrder(),
                    subject.isHasStandardScore(), subject.isHasPercentile(),
                    subject.isHasGradeLevel());
        }
    }
}
