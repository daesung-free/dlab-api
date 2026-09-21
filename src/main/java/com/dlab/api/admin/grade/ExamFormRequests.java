package com.dlab.api.admin.grade;

import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.service.ExamFormAdminService;
import com.dlab.domain.user.entity.GradeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
     * @param purpose    비우면 <b>입학 전 성적</b> 양식이다(기존 동작). 디랩에서 본 시험은
     *                   {@code ACADEMY} — 성적 업로드는 이 양식에만 된다
     * @param examDate   시행일. <b>{@code ACADEMY} 는 필수</b> — 월례고사가 코드만으로는
     *                   달이 구분되지 않는다
     * @param subjects   과목. <b>디랩 시험({@code ACADEMY})은 비워도 된다</b> — 학년별 기본 구성
     *                   ({@code GET /exam-forms/subject-presets})으로 채운다. 입학 전 성적은 필수
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
            Integer sortOrder,
            @Valid List<ExamFormSubject> subjects,
            com.dlab.domain.grade.entity.ExamPurpose purpose,
            java.time.LocalDate examDate) {

        /** 생략 가능 — 없으면 0. primitive 로 두면 생략만으로 역직렬화가 깨진다. */
        public int sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }

        public ExamFormAdminService.Command toCommand() {
            return new ExamFormAdminService.Command(academyId, year, gradeType, examCode,
                    // ★ sortOrder 는 생략 가능하다 — 그대로 넘기면 int 로 풀리며 NPE 가 난다
                    examName, sortOrderOrZero(),
                    subjects == null ? List.of() : subjects.stream()
                            .map(ExamFormSubject::toInput)
                            .toList(),
                    purpose, examDate);
        }
    }

    /**
     * @param subjectCode 통계 축. 학년이 달라도 국어끼리 묶이게 하는 값이라
     *                    표시명과 분리한다 — 표시명("통합사회")은 해마다 바뀐다
     * @param hasRawScore 원점수를 받는가. <b>비우면 디랩 시험은 켜고 입학 전 성적은 끈다</b>
     * @param hasStandardScore 한국사처럼 절대평가 과목은 {@code false}로 둘 것.
     *                         일괄로 열면 학생이 없는 점수를 지어내 채운다
     */
    public record ExamFormSubject(
            @NotBlank(message = "과목 코드는 필수입니다.") @Size(max = 20) String subjectCode,
            @NotBlank(message = "과목명은 필수입니다.") @Size(max = 30) String subjectName,
            int sortOrder,
            boolean hasStandardScore,
            boolean hasPercentile,
            boolean hasGradeLevel,
            Boolean hasRawScore) {

        public ExamFormAdminService.SubjectInput toInput() {
            return new ExamFormAdminService.SubjectInput(subjectCode, subjectName, sortOrder,
                    hasStandardScore, hasPercentile, hasGradeLevel, hasRawScore);
        }
    }

    /**
     * 한 학년의 기본 과목 구성 교체.
     *
     * @param academyId 비우면 <b>전 지점 공통</b> — 본사만
     * @param subjects  비우면 그 범위의 행을 전부 지운다(지점 행이면 공통본으로 돌아간다).
     *                  {@code hasRawScore} 를 비우면 켠다 — 디랩 시험용 구성이다
     */
    public record PresetReplace(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "학년은 필수입니다.") GradeType gradeType,
            @NotNull(message = "과목 목록은 필수입니다.") @Valid List<ExamFormSubject> subjects) {
    }

    public record PresetView(Long presetId, Long academyId, short year, String gradeType,
                             String subjectCode, String subjectName, int sortOrder,
                             boolean hasStandardScore, boolean hasPercentile,
                             boolean hasGradeLevel, boolean hasRawScore) {

        public static PresetView from(com.dlab.domain.grade.entity.ExamSubjectPreset p) {
            return new PresetView(p.getId(), p.isCommon() ? null : p.getAcademy().getId(),
                    p.getYear(), p.getGradeType().name(), p.getSubjectCode(),
                    p.getSubjectName(), p.getSortOrder(), p.isHasStandardScore(),
                    p.isHasPercentile(), p.isHasGradeLevel(), p.isHasRawScore());
        }
    }

    /** @param academyId 비우면 공통본 — 본사만 */
    public record Rollover(
            Long academyId,
            @NotNull(message = "원본 연도는 필수입니다.") Short fromYear,
            @NotNull(message = "새 연도는 필수입니다.") Short toYear) {
    }

    /** 등록된 회차 한 줄. */
    /**
     * @param purpose  {@code ADMISSION}=입학 전 성적 / {@code ACADEMY}=디랩에서 본 시험.
     *                 <b>업로드 회차 목록은 {@code ACADEMY} 만 보여줄 것</b> — 입학 양식에 올리면
     *                 학생이 넣은 입학 성적이 교체된다(서버도 막는다)
     * @param examDate 시행일. 입학 양식은 비어 있다
     */
    public record FormView(Long examMasterId, Long academyId, short year, String gradeType,
                           String examCode, String examName, int sortOrder,
                           List<SubjectView> subjects, String purpose,
                           java.time.LocalDate examDate) {

        public static FormView from(ExamMaster exam) {
            return new FormView(exam.getId(),
                    exam.isCommon() ? null : exam.getAcademy().getId(),
                    exam.getYear(), exam.getGradeType().name(), exam.getExamCode().name(),
                    exam.getExamName(), exam.getSortOrder(),
                    exam.activeSubjects().stream().map(SubjectView::from).toList(),
                    exam.getPurpose().name(), exam.getExamDate());
        }
    }

    public record SubjectView(Long examSubjectId, String subjectCode, String subjectName,
                              int sortOrder, boolean hasStandardScore, boolean hasPercentile,
                              boolean hasGradeLevel, boolean hasRawScore) {

        static SubjectView from(ExamSubject subject) {
            return new SubjectView(subject.getId(), subject.getSubjectCode(),
                    subject.getSubjectName(), subject.getSortOrder(),
                    subject.isHasStandardScore(), subject.isHasPercentile(),
                    subject.isHasGradeLevel(), subject.isHasRawScore());
        }
    }
}
