package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.ExamSubject;
import com.dlab.domain.grade.entity.ExamUniversityChoice;
import com.dlab.domain.grade.entity.StudentExamScore;
import com.dlab.domain.grade.entity.StudentGradeSubmission;
import com.dlab.domain.grade.repository.ExamUniversityChoiceRepository;
import com.dlab.domain.grade.repository.StudentGradeSubmissionRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 디랩에서 본 시험 조회 (앱 성적 탭).
 *
 * <h2>입학 전 성적과 나눠서 내린다</h2>
 * 입학 전 성적은 기존 {@code GET /app/grades} 가 입학 양식 기준으로 내린다. 여기는
 * <b>디랩에서 본 시험({@code ACADEMY})만</b> 본다 — 시안도 두 가지를 다른 화면으로 나눈다
 * (「입학 때 입력한 성적」 / 「디랩 모의고사」).
 *
 * <h2>여기 없는 것 — 일부러 비워 둔다</h2>
 * <ul>
 *   <li><b>지점 안 등수 · 유사 학생 비교</b> — 다른 학생 성적에서 나오는 값이라 노출 여부가
 *       확정돼야 한다(시안 6장 2·3번 회신 대기)</li>
 *   <li><b>수능 환산 예상(백분위 합)</b> — 탐구를 평균(300점)으로 볼지 합산(400점)으로 볼지가
 *       확정돼야 한다(시안 6장 4번). 둘은 다른 숫자라 먼저 내리면 나중에 학생이 보는 값이 바뀐다</li>
 *   <li><b>채점(문항별 정오·전국 정답률)</b> — 정오표·문항분석표 파서가 아직 없다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AcademyExamQueryService {

    private final StudentGradeSubmissionRepository submissionRepository;
    private final ExamUniversityChoiceRepository choiceRepository;

    /**
     * 내가 본 디랩 시험 — 최근순.
     *
     * <p><b>성적이 있는 회차만</b> 내린다. 등록된 회차 전부를 내리면 아직 안 본 시험이 빈 카드로
     * 뜬다.
     */
    @Transactional(readOnly = true)
    public List<ExamSummary> exams(StudentEnrollment enrollment) {
        return academyScores(enrollment).stream()
                .map(StudentExamScore::getExamMaster)
                .distinct()
                .sorted(Comparator.comparing(ExamMaster::getExamDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ExamSummary::of)
                .toList();
    }

    /** 한 회차의 과목별 성적 + 지망대학 진단. */
    @Transactional(readOnly = true)
    public ExamDetail exam(StudentEnrollment enrollment, Long examMasterId) {
        List<StudentExamScore> scores = academyScores(enrollment).stream()
                .filter(s -> s.getExamMaster().getId().equals(examMasterId))
                .toList();
        if (scores.isEmpty()) {
            // 남의 회차·안 본 회차·입학 양식을 구분해 알려주지 않는다 — 존재 여부가 새지 않게
            throw new BusinessException(ErrorCode.NOT_FOUND, "성적을 찾을 수 없습니다.");
        }
        ExamMaster exam = scores.get(0).getExamMaster();

        Map<Long, StudentExamScore> bySubject = scores.stream()
                .collect(Collectors.toMap(s -> s.getExamSubject().getId(), s -> s, (a, b) -> a));
        List<SubjectScore> subjects = exam.activeSubjects().stream()
                .filter(sub -> bySubject.containsKey(sub.getId()))
                .map(sub -> SubjectScore.of(sub, bySubject.get(sub.getId())))
                .toList();

        List<Choice> choices = choiceRepository.findByEnrollmentId(enrollment.getId()).stream()
                .filter(c -> c.getExamMaster().getId().equals(examMasterId))
                .map(Choice::of)
                .toList();

        return new ExamDetail(ExamSummary.of(exam), subjects, choices,
                exam.getExamCode() != ExamCode.MONTHLY);
    }

    /**
     * 성적 변화 — 회차별 과목 등급·백분위.
     *
     * <p>디랩 시험만 담는다. 입학 전 성적은 <b>학생이 적고 선생님이 대조한 값</b>이라 출처가
     * 달라서, 한 줄로 이을지는 화면이 정한다(시안 확인 대기) — 필요하면 {@code GET /app/grades}
     * 와 합쳐 그린다.
     */
    @Transactional(readOnly = true)
    public List<TrendPoint> trend(StudentEnrollment enrollment) {
        Map<ExamMaster, List<StudentExamScore>> byExam = academyScores(enrollment).stream()
                .collect(Collectors.groupingBy(StudentExamScore::getExamMaster));
        return byExam.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getExamDate(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(e -> new TrendPoint(ExamSummary.of(e.getKey()),
                        e.getValue().stream()
                                .sorted(Comparator.comparing(s -> s.getExamSubject().getSortOrder()))
                                .map(s -> new TrendValue(s.getExamSubject().getSubjectCode(),
                                        s.getExamSubject().getSubjectName(),
                                        s.getGradeLevel(), s.getPercentile()))
                                .toList()))
                .toList();
    }

    private List<StudentExamScore> academyScores(StudentEnrollment enrollment) {
        return submissionRepository.findByEnrollmentId(enrollment.getId())
                .map(StudentGradeSubmission::activeScores)
                .orElse(List.of())
                .stream()
                .filter(s -> s.getExamMaster().isAcademyExam())
                .toList();
    }

    /**
     * @param kice 평가원 모의고사인가. 시안 4.1 이 「평가원」 표시로 구분하고, 4.7 이
     *             "모의평가에는 지망대학 진단이 없다" 안내를 띄운다
     */
    public record ExamSummary(Long examMasterId, String examCode, String examName,
                              LocalDate examDate, boolean kice) {

        static ExamSummary of(ExamMaster exam) {
            boolean kice = exam.getExamCode() == ExamCode.JUNE
                    || exam.getExamCode() == ExamCode.SEPT;
            return new ExamSummary(exam.getId(), exam.getExamCode().name(), exam.getExamName(),
                    exam.getExamDate(), kice);
        }
    }

    /**
     * 과목 한 칸. <b>없는 값은 {@code null}</b> 이다 — 0 으로 내리면 진짜 0점과 구분되지 않는다.
     * 영어·한국사는 절대평가라 표준점수·백분위가 비고 원점수·등급만 있다.
     */
    public record SubjectScore(String subjectCode, String subjectName, Short rawScore,
                               Short standardScore, Short percentile, Short gradeLevel) {

        static SubjectScore of(ExamSubject subject, StudentExamScore score) {
            return new SubjectScore(subject.getSubjectCode(), subject.getSubjectName(),
                    score.getRawScore(), score.getStandardScore(), score.getPercentile(),
                    score.getGradeLevel());
        }
    }

    /**
     * 지망대학 한 줄 — 연구소 판정 그대로.
     *
     * @param gapToCutoff 기준점수까지 남은 점수. 음수면 넘었다. 시안이 단계 이름과 함께
     *                    보여준다(지망을 적은 학생의 약 84% 가 「위험」이라 단계만으로는
     *                    무엇을 해야 할지 알 수 없다)
     */
    public record Choice(int rank, String universityName, String departmentName,
                         Integer recruitQuota, Integer applicantCount, Integer applicantRank,
                         String appliedAreas, java.math.BigDecimal expectedScore,
                         java.math.BigDecimal cutoffScore, java.math.BigDecimal gapToCutoff,
                         String diagnosis) {

        static Choice of(ExamUniversityChoice c) {
            return new Choice(c.getChoiceRank(), c.getUniversityName(), c.getDepartmentName(),
                    c.getRecruitQuota(), c.getApplicantCount(), c.getApplicantRank(),
                    c.getAppliedAreas(), c.getExpectedScore(), c.getCutoffScore(),
                    c.gapToCutoff(), c.getDiagnosis());
        }
    }

    /**
     * @param choices          지망대학. 적지 않았으면 비어 있다(시안 4.7 안내 화면)
     * @param noDiagnosisByType 이 회차는 진단 자체가 오지 않는다(평가원·수능). 비어 있는
     *                          이유가 "안 적어서"인지 "원래 없어서"인지 화면이 구분하게 한다
     */
    public record ExamDetail(ExamSummary exam, List<SubjectScore> subjects,
                             List<Choice> choices, boolean noDiagnosisByType) {
    }

    public record TrendPoint(ExamSummary exam, List<TrendValue> values) {
    }

    public record TrendValue(String subjectCode, String subjectName, Short gradeLevel,
                             Short percentile) {
    }
}
