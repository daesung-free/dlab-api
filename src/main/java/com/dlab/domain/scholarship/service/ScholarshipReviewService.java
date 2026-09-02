package com.dlab.domain.scholarship.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.StudentExamScore;
import com.dlab.domain.grade.repository.StudentGradeSubmissionRepository;
import com.dlab.domain.master.entity.Scholarship;
import com.dlab.domain.master.repository.ScholarshipRepository;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.scholarship.entity.*;
import com.dlab.domain.scholarship.repository.ScholarshipCancelRuleRepository;
import com.dlab.domain.scholarship.repository.ScholarshipReviewRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 장학 취소 판정 (0820 규정 · 방식 2026-08-26 클라이언트 승인).
 *
 * <h2>★ 자동으로 취소하지 않는다</h2>
 * 판정은 자동이고 <b>확정은 사람</b>이다. 시트가 <i>"개인사정에 의해 응시를 못할 경우
 * 더프모 성적으로 대체하는 경우도 있다"</i>, <i>"예외를 두는 경우가 많이 발생한다"</i>고
 * 명시했다 — 자동 확정하면 <b>예외인 학생 장학금이 조용히 날아간다.</b>
 *
 * <h2>기준은 데이터다</h2>
 * 임계값·대상 과목이 {@code scholarship_cancel_rule}에 있고 <b>기본은 꺼져 있다.</b>
 * {@code penalty_rule}과 같은 방식이다.
 *
 * <h2>★ 기준은 "한 줄"이 아니다 (0826 답변서)</h2>
 * 장학 등급마다 기준이 다르고, 그 안에서 <b>OR 대안</b>과 <b>AND 조건</b>이 함께 걸린다.
 * <pre>
 *   수능 100%   (국+수+탐2평균) ≤ 4  또는  (국+수+영) ≤ 4
 *   평가원 50%  (국+수+탐1)     ≤ 4  그리고 영어 ≤ 2등급
 * </pre>
 * 그래서 규칙 <b>여러 행이 한 판정</b>을 이룬다 — {@code alternative_group}이 다르면
 * 대안이고, <b>하나라도 충족하면 통과</b>다. 반대로 하면 유리한 쪽을 골라주는
 * 규정 취지가 뒤집힌다.
 *
 * <h2>★ 장학이 없는 학생은 대상이 아니다</h2>
 * 취소할 장학이 없다. 게다가 <b>어느 장학인지 모르면 어떤 기준을 적용할지도 정해지지
 * 않는다</b> — 전 학생을 훑으면 검토 목록이 무의미하게 불어난다.
 *
 * <h2>지금 판정할 수 있는 것 / 없는 것</h2>
 * <ul>
 *   <li><b>벌점 누적</b> — 된다. 상벌점 데이터가 있다</li>
 *   <li><b>등급합</b> — 된다. ⚠️ 다만 <b>수능 기준 장학의 판정 시점</b>이 미확정이라
 *       규칙을 꺼둔 상태다(§4). 수능 성적은 12월에 나오는데 그때는 그 해 교습비를
 *       다 낸 뒤라, 당해 취소냐 다음 해 재등록 심사냐에 따라 환불이 돌고 안 돌고가 갈린다</li>
 *   <li><b>더프리미엄 미응시</b> — 성적표가 매월 나오므로 <b>월별 성적 등록 이력이
 *       곧 응시 증거</b>다. 판정 자체는 가능하지만 아직 적재 경로가 없다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipReviewService {

    /**
     * 탐구 과목코드. {@code exam_subject} 양식에 고정된 값이라 규칙 컬럼으로 두지 않았다 —
     * 학생이 고르는 것은 <b>과목명</b>이지 이 자리 자체가 아니다(신상기록부 양식 기준).
     */
    private static final String ELECTIVE_1 = "INQUIRY1";
    private static final String ELECTIVE_2 = "INQUIRY2";

    private final ScholarshipCancelRuleRepository ruleRepository;
    private final ScholarshipReviewRepository reviewRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final PenaltyPointRepository penaltyPointRepository;
    private final StudentGradeSubmissionRepository gradeRepository;
    private final ScholarshipRepository scholarshipRepository;
    private final Clock clock;

    /**
     * 지점 전체를 판정해 <b>검토 대상</b>을 올린다.
     *
     * <p>이미 올라온 건은 건너뛴다 — 배치가 여러 번 돌아도 목록이 중복으로 쌓이면
     * 담당자가 같은 학생을 반복해서 본다.
     *
     * @return 새로 올라온 검토 대상
     */
    @Transactional
    public List<ScholarshipReview> judge(AuthPrincipal me, Long academyId, short year) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        List<ScholarshipCancelRule> rules = ruleRepository.findActive(year, academyId);
        if (rules.isEmpty()) {
            // 규칙이 꺼져 있으면 아무 일도 안 일어난다. 조용히 0건이 아니라 로그를 남긴다 —
            // "판정했는데 왜 0건이지"를 규칙 미설정과 구분할 수 있어야 한다
            log.info("장학 취소 판정 — 켜진 규칙이 없다: academyId={}, year={}", academyId, year);
            return List.of();
        }

        // ★ 직원은 대상이 아니다. findCurrentByAcademyId 가 이미 STAFF 를 뺀다
        List<StudentEnrollment> students = enrollmentRepository.findCurrentByAcademyId(academyId);
        List<ScholarshipReview> created = new ArrayList<>();

        for (StudentEnrollment enrollment : students) {
            // ★ 장학이 없으면 취소할 것도, 적용할 기준도 없다
            Set<String> scholarshipTypes = scholarshipTypesOf(enrollment);
            if (scholarshipTypes.isEmpty()) {
                continue;
            }

            // 요건별로 묶는다 — 같은 요건의 여러 행은 서로 대안(OR)이라 함께 봐야 한다
            Map<CancelRuleType, List<ScholarshipCancelRule>> byRuleType = rules.stream()
                    .filter(r -> scholarshipTypes.stream().anyMatch(r::appliesTo))
                    .collect(Collectors.groupingBy(ScholarshipCancelRule::getRuleType));

            for (var entry : byRuleType.entrySet()) {
                Detection detection = detect(enrollment, entry.getKey(), entry.getValue());
                if (detection == null) {
                    continue;
                }
                if (reviewRepository.existsPending(enrollment.getId(), year, entry.getKey())) {
                    continue;
                }
                created.add(reviewRepository.save(new ScholarshipReview(
                        enrollment, entry.getKey(), detection.value(),
                        detection.threshold(), detection.detail())));
            }
        }

        log.info("장학 취소 판정: academyId={}, year={}, 규칙={}건, 신규 검토대상={}건",
                academyId, year, rules.size(), created.size());
        return created;
    }

    /** 검토 대상 목록. */
    @Transactional(readOnly = true)
    public List<ScholarshipReview> findReviews(AuthPrincipal me, Long academyId, short year,
                                               ReviewStatus status) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return reviewRepository.findByScope(academyId, year, status);
    }

    /**
     * 취소 확정.
     *
     * <p>⚠️ <b>여기서 장학금을 되받지는 않는다.</b> 그건 퇴원 정산에서
     * {@code RetroactiveChargeService}가 하고, 데스크가 "정상가 재결제 / 재결제 없이"를
     * 고른다(0820 규정). 취소와 정산은 시점이 다르다.
     */
    @Transactional
    public ScholarshipReview cancel(AuthPrincipal me, Long reviewId, String note) {
        ScholarshipReview review = requireReview(me, reviewId);
        review.cancel(me.accountId(), note, Instant.now(clock));
        log.info("장학 취소 확정: reviewId={}, enrollmentId={}, 처리자={}",
                reviewId, review.getEnrollment().getId(), me.accountId());
        return review;
    }

    /**
     * 예외 인정.
     *
     * <p><b>사유가 필수다.</b> 없으면 나중에 "왜 살려뒀나"에 답할 수 없고,
     * 예외가 반복되면 기준 자체가 무의미해진다.
     */
    @Transactional
    public ScholarshipReview except(AuthPrincipal me, Long reviewId, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.SCHOLARSHIP_EXCEPTION_NOTE_REQUIRED);
        }
        ScholarshipReview review = requireReview(me, reviewId);
        review.except(me.accountId(), note, Instant.now(clock));
        log.info("장학 예외 인정: reviewId={}, enrollmentId={}, 사유={}",
                reviewId, review.getEnrollment().getId(), note);
        return review;
    }

    // ─────────────────────────────────────────── 판정

    private Detection detect(StudentEnrollment enrollment, CancelRuleType ruleType,
                             List<ScholarshipCancelRule> alternatives) {
        return switch (ruleType) {
            case PENALTY_POINT -> detectPenalty(enrollment, alternatives.get(0));
            case EXAM_GRADE_SUM -> detectGradeSum(enrollment, alternatives);
            // 성적표가 매월 오므로 월별 등록 이력으로 판정 가능하지만 적재 경로가 아직 없다
            case MOCK_EXAM_ABSENCE -> null;
        };
    }

    /** 벌점 누적. 임계값 <b>이상</b>이면 걸린다(규정 "40점 이상"). */
    private Detection detectPenalty(StudentEnrollment enrollment, ScholarshipCancelRule rule) {
        int points = penaltyPointRepository.sumPointsByEnrollment(enrollment.getId());
        if (points < rule.getThreshold()) {
            return null;
        }
        return new Detection(points, rule.getThreshold(),
                "벌점 %d점 (기준 %d점 이상). 제적 기준과 같은 값이다"
                        .formatted(points, rule.getThreshold()));
    }

    /**
     * 등급합 — <b>대안 중 하나라도 충족하면 통과</b>다.
     *
     * <p>규정이 <i>"(국+수+탐) 또는 (국+수+영)"</i>이라 유리한 쪽을 골라준다.
     * 전부 미달일 때만 검토 대상이 되고, 그때 <b>가장 근접했던 대안</b>을 근거로 남긴다 —
     * 담당자가 예외를 판단하려면 "얼마나 모자랐나"를 봐야 한다.
     *
     * <p>⚠️ 값은 <b>2배 스케일</b>로 저장한다. 탐구 2과목 평균이 3.5처럼 정수가
     * 아닐 수 있어서, 반올림해 비교하면 경계에서 한 칸씩 어긋난다.
     */
    private Detection detectGradeSum(StudentEnrollment enrollment,
                                     List<ScholarshipCancelRule> alternatives) {
        var submission = gradeRepository.findByEnrollmentId(enrollment.getId()).orElse(null);
        if (submission == null) {
            // 성적을 안 낸 학생은 판정 대상이 아니다. "미제출"과 "기준 미달"은 다르다
            return null;
        }
        List<StudentExamScore> scores = submission.activeScores();

        List<AltResult> results = new ArrayList<>();
        for (ScholarshipCancelRule rule : alternatives) {
            AltResult result = evaluate(scores, rule);
            if (result == null) {
                continue;   // 판정 불가한 대안은 없는 셈 친다
            }
            if (result.passed()) {
                return null;   // ★ 하나라도 충족하면 통과
            }
            results.add(result);
        }
        if (results.isEmpty()) {
            return null;
        }

        // 가장 근접했던 대안 = (등급합 − 임계값)이 가장 작은 것
        AltResult closest = results.stream()
                .min(Comparator.comparingInt(r -> r.doubledSum() - r.doubledThreshold()))
                .orElseThrow();

        String detail = results.stream().map(AltResult::describe)
                .collect(Collectors.joining(" / ", "모든 대안 미달 — ", ""));
        return new Detection(closest.doubledSum(), closest.doubledThreshold(), detail);
    }

    /**
     * 대안 하나를 평가한다. 판정할 수 없으면 {@code null}.
     *
     * <p>대상 회차가 여럿이면 <b>좋은 쪽</b>을 쓴다 — 한 번 못 본 시험 때문에
     * 장학이 날아가면 안 된다.
     */
    private AltResult evaluate(List<StudentExamScore> scores, ScholarshipCancelRule rule) {
        List<String> fixed = rule.subjects();
        if (fixed.isEmpty()) {
            // ★ 대상 과목이 비어 있으면 판정하지 않는다 — 임의로 고르면 엉뚱한 학생이 걸린다
            return null;
        }

        AltResult best = null;
        for (ExamCode examCode : rule.examCodes()) {
            Integer doubledSum = doubledSumOf(scores, examCode, fixed, rule.getElectiveMode());
            if (doubledSum == null) {
                continue;
            }
            Boolean extraOk = extraConditionMet(scores, examCode, rule);
            if (extraOk == null) {
                continue;   // AND 조건 과목의 등급이 없으면 그 회차는 못 쓴다
            }
            AltResult candidate = new AltResult(rule, examCode, doubledSum,
                    rule.getThreshold() * 2, extraOk);
            // 통과한 회차가 있으면 그게 최선이다. 없으면 합이 작은 쪽
            if (best == null || candidate.betterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 그 회차의 등급합 <b>×2</b>. 고정 과목은 등급×2, 탐구는 집계 방식에 따른다.
     *
     * <p><b>한 과목이라도 등급이 없으면 {@code null}</b>이다 — 빠진 과목을 0으로 세면
     * 합이 작아져 <b>미달인 학생이 통과</b>한다.
     */
    private Integer doubledSumOf(List<StudentExamScore> scores, ExamCode examCode,
                                 List<String> fixedSubjects, ElectiveMode electiveMode) {
        int sum = 0;
        for (String subject : fixedSubjects) {
            Short grade = gradeOf(scores, examCode, subject);
            if (grade == null) {
                return null;
            }
            sum += grade * 2;
        }
        if (electiveMode == null) {
            return sum;
        }

        Short first = gradeOf(scores, examCode, ELECTIVE_1);
        Short second = gradeOf(scores, examCode, ELECTIVE_2);
        return switch (electiveMode) {
            // 좋은 쪽 1과목 — 등급은 작을수록 좋다. 한 과목만 있어도 성립한다
            case SINGLE -> {
                if (first == null && second == null) {
                    yield null;
                }
                int pick = Math.min(first == null ? Short.MAX_VALUE : first,
                        second == null ? Short.MAX_VALUE : second);
                yield sum + pick * 2;
            }
            // 2과목 평균 — 평균×2 = 두 등급의 합. 둘 다 있어야 한다
            case AVG2 -> (first == null || second == null) ? null : sum + first + second;
        };
    }

    /** AND 조건 충족 여부. 조건이 없으면 {@code true}, 등급을 알 수 없으면 {@code null}. */
    private Boolean extraConditionMet(List<StudentExamScore> scores, ExamCode examCode,
                                      ScholarshipCancelRule rule) {
        if (!rule.hasExtraCondition()) {
            return true;
        }
        Short grade = gradeOf(scores, examCode, rule.getExtraSubjectCode());
        return grade == null ? null : grade <= rule.getExtraMaxGrade();
    }

    private Short gradeOf(List<StudentExamScore> scores, ExamCode examCode, String subjectCode) {
        for (StudentExamScore score : scores) {
            if (score.getExamMaster().getExamCode() == examCode
                    && score.getExamSubject().getSubjectCode().equals(subjectCode)) {
                return score.getGradeLevel();
            }
        }
        return null;
    }

    private ScholarshipReview requireReview(AuthPrincipal me, Long reviewId) {
        ScholarshipReview review = reviewRepository.findById(reviewId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHOLARSHIP_REVIEW_NOT_FOUND));
        if (!me.canAccessAcademy(review.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (review.getStatus().isDecided()) {
            throw new BusinessException(ErrorCode.SCHOLARSHIP_REVIEW_ALREADY_DECIDED);
        }
        return review;
    }

    /**
     * 그 학생의 장학 종류들. 비어 있으면 판정 대상이 아니다 — 취소할 것이 없다.
     *
     * <p><b>여러 건이면 전부 본다.</b> 하나만 골라 쓰면 어느 것이 뽑히느냐에 따라
     * 판정이 달라져 <b>같은 학생이 실행할 때마다 걸렸다 안 걸렸다 한다.</b>
     */
    private Set<String> scholarshipTypesOf(StudentEnrollment enrollment) {
        return scholarshipRepository.findByEnrollmentId(enrollment.getId()).stream()
                .map(Scholarship::getScholarshipType).collect(Collectors.toSet());
    }

    /**
     * @param value     걸린 실제 값. 검토 화면이 "왜 걸렸는지"를 보여주는 근거다
     * @param threshold 판정 당시 임계값. ⚠️ 등급합은 <b>둘 다 2배 스케일</b>이다
     */
    private record Detection(int value, int threshold, String detail) {
    }

    /** 대안 하나의 평가 결과. 값은 전부 <b>2배 스케일</b>이다. */
    private record AltResult(ScholarshipCancelRule rule, ExamCode examCode,
                             int doubledSum, int doubledThreshold, boolean extraOk) {

        /** 등급합이 기준 이하이고 AND 조건까지 충족해야 통과다. */
        boolean passed() {
            return doubledSum <= doubledThreshold && extraOk;
        }

        /** 통과한 쪽이 낫고, 둘 다 아니면 합이 작은 쪽이 낫다. */
        boolean betterThan(AltResult other) {
            if (passed() != other.passed()) {
                return passed();
            }
            return doubledSum < other.doubledSum;
        }

        String describe() {
            String elective = rule.getElectiveMode() == null ? ""
                    : switch (rule.getElectiveMode()) {
                        case SINGLE -> "+탐구1과목";
                        case AVG2 -> "+탐구2과목평균";
                    };
            String extra = rule.hasExtraCondition()
                    ? " · %s %d등급 이내 %s".formatted(rule.getExtraSubjectCode(),
                            rule.getExtraMaxGrade(), extraOk ? "충족" : "미충족")
                    : "";
            return "[%s] %s%s 등급합 %s (기준 %d 이하)%s".formatted(
                    examCode, String.join("·", rule.subjects()), elective,
                    format(doubledSum), rule.getThreshold(), extra);
        }

        /** 2배 스케일을 사람이 읽는 값으로. 홀수면 .5다. */
        private static String format(int doubled) {
            return doubled % 2 == 0 ? String.valueOf(doubled / 2) : (doubled / 2.0) + "";
        }
    }
}
