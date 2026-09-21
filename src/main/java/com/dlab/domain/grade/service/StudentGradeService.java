package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.entity.StudentExamScore;
import com.dlab.domain.grade.entity.StudentGradeSubmission;
import com.dlab.domain.grade.repository.StudentGradeSubmissionRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 학생 성적 제출·조회 (앱 A-2 · 상담 기초자료).
 *
 * <h2>가입과 분리돼 있다</h2>
 * 가입 트랜잭션에 묶지 않는다. 성적은 시험 3회차 × 과목 6개까지 되는 긴 입력이라,
 * 한 번에 받으면 중간에 실패했을 때 <b>휴대폰 인증부터 다시</b> 해야 한다.
 *
 * <h2>덮어쓰기가 기본이다</h2>
 * 학생이 화면에서 표를 통째로 다시 채워 보내는 흐름이라 <b>제출 = 그 회차 전체 교체</b>다.
 * 부분 갱신으로 두면 "지웠는데 남아 있는" 칸이 생긴다. 학습계획에서 같은 판단을 했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentGradeService {

    private final StudentGradeSubmissionRepository submissionRepository;
    private final ExamFormService examFormService;
    private final Clock clock;

    /** 내 성적. 아직 낸 적 없으면 빈 제출을 만들어 돌려준다 — 화면이 분기하지 않게. */
    @Transactional
    public StudentGradeSubmission mine(StudentEnrollment enrollment) {
        return submissionRepository.findByEnrollmentId(enrollment.getId())
                .orElseGet(() -> submissionRepository.save(new StudentGradeSubmission(enrollment)));
    }

    @Transactional(readOnly = true)
    public StudentGradeSubmission of(StudentEnrollment enrollment) {
        return submissionRepository.findByEnrollmentId(enrollment.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.GRADE_SUBMISSION_NOT_FOUND));
    }

    /**
     * 직원이 고친 것으로 표시한다.
     *
     * <p>0826 회신이 <i>"처음 입력시 학생, 이후 수정시에는 직원을 통해서"</i>로 정했다.
     * 이 값이 <b>장학 취소 판정의 근거</b>라, 학생 입력값을 직원이 고쳤다면 그 사실이 남아야 한다.
     * {@code createdBy}는 {@code updatable = false}라 최초 작성자(= 학생)만 남는다.
     */
    @Transactional
    public StudentGradeSubmission markModified(StudentEnrollment enrollment, Long accountId) {
        StudentGradeSubmission submission = mine(enrollment);
        submission.markModifiedBy(accountId, Instant.now(clock));
        return submission;
    }

    /** 내신 주요교과평균. 값 하나뿐이라 별도 흐름을 두지 않는다. */
    @Transactional
    public StudentGradeSubmission saveSchoolRecord(StudentEnrollment enrollment,
                                                   BigDecimal mainSubjectAverage) {
        StudentGradeSubmission submission = mine(enrollment);
        submission.updateSchoolRecord(mainSubjectAverage);
        submission.markSubmitted(Instant.now(clock));
        return submission;
    }

    /**
     * 모의고사 성적 제출. <b>보낸 회차만</b> 교체하고 나머지 회차는 건드리지 않는다.
     *
     * <p>회차별로 나눠 낼 수 있어야 한다 — 6월 성적만 아는 학생이 9월·10월까지 채워야
     * 저장되는 구조면 아무것도 못 낸다.
     */
    @Transactional
    public StudentGradeSubmission saveExamScores(StudentEnrollment enrollment,
                                                 List<ScoreInput> inputs) {
        StudentGradeSubmission submission = mine(enrollment);
        List<ExamFormService.Form> forms = examFormService.formOf(enrollment);

        // ★ 과목 검증을 먼저 전부 끝낸다. 지우면서 검증하면 중간에 거절됐을 때
        //   앞 회차만 지워진 채로 롤백 경계가 애매해진다
        Map<Long, ExamSubject> subjects = inputs.stream()
                .map(in -> examFormService.requireSubject(forms, in.examSubjectId()))
                .collect(Collectors.toMap(ExamSubject::getId, Function.identity(), (a, b) -> a));

        // 값이 하나라도 들어왔으면 "모른다" 상태를 푼다
        if (inputs.stream().anyMatch(ScoreInput::hasValue)) {
            submission.unskipExams();
        }

        inputs.stream()
                .map(in -> subjects.get(in.examSubjectId()).getExamMaster().getId())
                .distinct()
                .forEach(submission::clearScoresOf);

        for (ScoreInput input : inputs) {
            if (!input.hasValue()) {
                // 빈 줄은 저장하지 않는다. 저장하면 "입력했는데 세 칸이 다 빈" 행이
                // 남아 미입력과 구분되지 않는다
                continue;
            }
            StudentExamScore score = submission.addScore(subjects.get(input.examSubjectId()),
                    input.standardScore(), input.percentile(), input.gradeLevel());
            if (score.isBlank()) {
                // 양식에 없는 칸만 채워 보낸 경우(한국사 표준점수 등) — 전부 걸러졌다
                score.markDeleted();
            }
        }
        submission.markSubmitted(Instant.now(clock));
        log.info("성적 제출: enrollmentId={}, 과목수={}", enrollment.getId(), inputs.size());
        return submission;
    }

    /**
     * 디랩에서 본 시험 성적 반영 — 연구소 파일 업로드 전용.
     *
     * <p>★ <b>{@link #saveExamScores} 와 경로를 나눈 이유가 둘이다.</b>
     * <ul>
     *   <li>저쪽은 <b>학생의 입학 양식</b>으로 과목을 검증한다. 디랩 시험 과목은 그 양식에
     *       없어서 전부 거절된다</li>
     *   <li>저쪽은 입학 성적의 <b>"제출 완료"·"모른다" 상태를 바꾼다</b>. 연구소 성적이
     *       들어왔다고 학생이 입학 성적을 낸 것으로 바뀌면 안 된다</li>
     * </ul>
     *
     * <p>그 회차 점수만 교체한다. 다른 회차와 입학 성적은 건드리지 않는다.
     */
    @Transactional
    public void saveAcademyScores(StudentEnrollment enrollment, ExamMaster exam,
                                  List<ScoreInput> inputs) {
        if (!exam.isAcademyExam()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "입학 전 성적 양식에는 반영할 수 없습니다.");
        }
        Map<Long, ExamSubject> subjects = exam.activeSubjects().stream()
                .collect(Collectors.toMap(ExamSubject::getId, Function.identity()));

        StudentGradeSubmission submission = mine(enrollment);
        submission.clearScoresOf(exam.getId());
        for (ScoreInput input : inputs) {
            ExamSubject subject = subjects.get(input.examSubjectId());
            if (subject == null || !input.hasValue()) {
                continue;
            }
            StudentExamScore score = submission.addScore(subject,
                    input.standardScore(), input.percentile(), input.gradeLevel(),
                    input.rawScore());
            if (score.isBlank()) {
                score.markDeleted();
            }
        }
    }

    /**
     * 모의고사 성적을 모른다고 체크.
     *
     * <p>0으로 채우게 두면 통계에서 진짜 0점과 구분되지 않는다. 사유를 남기고 건너뛴다 —
     * 나중에 상담 교사가 "왜 없는지"를 물어볼 수 있어야 한다.
     */
    @Transactional
    public StudentGradeSubmission skipExams(StudentEnrollment enrollment, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.GRADE_SKIP_REASON_REQUIRED);
        }
        StudentGradeSubmission submission = mine(enrollment);
        submission.skipExams(reason);
        submission.markSubmitted(Instant.now(clock));
        return submission;
    }

    /** 과목 한 칸. 세 값 모두 {@code null}일 수 있다 — 미응시·절대평가·기억 안 남. */
    /**
     * @param rawScore 원점수. 학생 입력(입학 전 성적)에는 없다 — 연구소 파일에서만 온다
     */
    public record ScoreInput(Long examSubjectId, Short standardScore, Short percentile,
                             Short gradeLevel, Short rawScore) {

        /** 원점수가 없는 입력(학생·직원 입력). */
        public ScoreInput(Long examSubjectId, Short standardScore, Short percentile,
                          Short gradeLevel) {
            this(examSubjectId, standardScore, percentile, gradeLevel, null);
        }

        boolean hasValue() {
            return standardScore != null || percentile != null || gradeLevel != null
                    || rawScore != null;
        }
    }
}
