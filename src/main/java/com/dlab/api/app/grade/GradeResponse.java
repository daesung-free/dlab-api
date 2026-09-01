package com.dlab.api.app.grade;

import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.entity.StudentExamScore;
import com.dlab.domain.grade.entity.StudentGradeSubmission;
import com.dlab.domain.grade.service.ExamFormService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 성적 응답 DTO. */
public final class GradeResponse {

    private GradeResponse() {
    }

    /**
     * 성적 입력 양식 한 회차.
     *
     * <p><b>앱은 이 결과대로 화면을 그린다</b> — 과목명·칸 구성을 앱이 자체 판정하지 않는다.
     * 그래야 2028 수능 개편으로 과목이 바뀔 때 앱 배포 없이 데이터만 고치면 된다.
     */
    public record Form(Long examMasterId, String examCode, String examName,
                       List<GradeSubject> subjects) {

        public static Form from(ExamFormService.Form form) {
            ExamMaster exam = form.exam();
            return new Form(exam.getId(), exam.getExamCode().name(), exam.getExamName(),
                    form.subjects().stream().map(GradeSubject::from).toList());
        }
    }

    /**
     * @param hasStandardScore 한국사처럼 절대평가 과목은 {@code false}.
     *                         <b>앱은 이 값이 false인 칸을 아예 그리지 말아야 한다</b> —
     *                         열어두면 학생이 없는 점수를 지어내 채운다
     */
    public record GradeSubject(Long examSubjectId, String subjectCode, String subjectName,
                          boolean hasStandardScore, boolean hasPercentile,
                          boolean hasGradeLevel) {

        public static GradeSubject from(ExamSubject subject) {
            return new GradeSubject(subject.getId(), subject.getSubjectCode(), subject.getSubjectName(),
                    subject.isHasStandardScore(), subject.isHasPercentile(),
                    subject.isHasGradeLevel());
        }
    }

    /**
     * 내가 낸 성적.
     *
     * @param examSkipped {@code true}면 <b>모른다고 체크한 것</b>이지 미입력이 아니다.
     *                    이때 {@code scores}는 비어 있고 {@code skipReason}이 채워진다
     */
    public record Submission(BigDecimal mainSubjectAverage, boolean examSkipped,
                             String skipReason, Instant submittedAt,
                             List<ExamResult> exams) {

        public static Submission from(StudentGradeSubmission submission,
                                      List<ExamFormService.Form> forms) {
            Map<Long, List<StudentExamScore>> byExam = submission.activeScores().stream()
                    .collect(Collectors.groupingBy(s -> s.getExamMaster().getId()));

            // ★ 양식 순서대로 내린다. 저장된 점수 순서로 내리면 학생이 6월만 냈을 때
            //   9월·10월 회차가 응답에서 통째로 사라져 앱이 입력 칸을 못 그린다
            List<ExamResult> exams = forms.stream()
                    .map(form -> ExamResult.of(form, byExam.getOrDefault(form.exam().getId(), List.of())))
                    .toList();

            return new Submission(submission.getMainSubjectAverage(), submission.isExamSkipped(),
                    submission.getSkipReason(), submission.getSubmittedAt(), exams);
        }
    }

    public record ExamResult(Long examMasterId, String examCode, String examName,
                             List<ScoreItem> scores) {

        static ExamResult of(ExamFormService.Form form, List<StudentExamScore> scores) {
            Map<Long, StudentExamScore> bySubject = scores.stream()
                    .collect(Collectors.toMap(s -> s.getExamSubject().getId(), s -> s, (a, b) -> a));

            ExamMaster exam = form.exam();
            return new ExamResult(exam.getId(), exam.getExamCode().name(), exam.getExamName(),
                    form.subjects().stream()
                            .map(subject -> ScoreItem.of(subject, bySubject.get(subject.getId())))
                            .toList());
        }
    }

    /** 점수가 없으면 세 칸이 {@code null}이다 — 0으로 내리면 진짜 0점과 구분되지 않는다. */
    public record ScoreItem(Long examSubjectId, String subjectName,
                            Short standardScore, Short percentile, Short gradeLevel) {

        static ScoreItem of(ExamSubject subject, StudentExamScore score) {
            return new ScoreItem(subject.getId(), subject.getSubjectName(),
                    score == null ? null : score.getStandardScore(),
                    score == null ? null : score.getPercentile(),
                    score == null ? null : score.getGradeLevel());
        }
    }
}
