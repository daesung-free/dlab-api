package com.dlab.domain.scholarship.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.StudentExamScore;
import com.dlab.domain.grade.repository.StudentGradeSubmissionRepository;
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
import java.util.List;
import java.util.Set;

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
 * <h2>지금 판정할 수 있는 것 / 없는 것</h2>
 * <ul>
 *   <li><b>벌점 누적</b> — 된다. 상벌점 데이터가 있다</li>
 *   <li><b>등급합</b> — 계산은 되지만 ⚠️ <b>"3과목"이 어느 과목인지 미확인</b>이라
 *       규칙을 꺼둔 상태다. 국·수·영으로 추정해 넣어 뒀다</li>
 *   <li><b>더프리미엄 미응시</b> — ⚠️ <b>판정 수단이 없다.</b> 응시 이력이 우리 DB에
 *       없어서 E-2(API 명세) 전까지는 켜도 아무것도 못 찾는다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScholarshipReviewService {

    private final ScholarshipCancelRuleRepository ruleRepository;
    private final ScholarshipReviewRepository reviewRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final PenaltyPointRepository penaltyPointRepository;
    private final StudentGradeSubmissionRepository gradeRepository;
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
            for (ScholarshipCancelRule rule : rules) {
                Detection detection = detect(enrollment, rule);
                if (detection == null) {
                    continue;
                }
                if (reviewRepository.existsPending(enrollment.getId(), year, rule.getRuleType())) {
                    continue;
                }
                created.add(reviewRepository.save(new ScholarshipReview(
                        enrollment, rule.getRuleType(), detection.value(),
                        rule.getThreshold(), detection.detail())));
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

    private Detection detect(StudentEnrollment enrollment, ScholarshipCancelRule rule) {
        return switch (rule.getRuleType()) {
            case PENALTY_POINT -> detectPenalty(enrollment, rule);
            case EXAM_GRADE_SUM -> detectGradeSum(enrollment, rule);
            // ⚠️ 응시 이력이 없어 판정 자체가 불가능하다. 규칙을 켜도 아무것도 안 나온다
            case MOCK_EXAM_ABSENCE -> null;
        };
    }

    /** 벌점 누적. 임계값 <b>이상</b>이면 걸린다(규정 "40점 이상"). */
    private Detection detectPenalty(StudentEnrollment enrollment, ScholarshipCancelRule rule) {
        int points = penaltyPointRepository.sumPointsByEnrollment(enrollment.getId());
        if (points < rule.getThreshold()) {
            return null;
        }
        return new Detection(points,
                "벌점 %d점 (기준 %d점 이상). 제적 기준과 같은 값이다"
                        .formatted(points, rule.getThreshold()));
    }

    /**
     * 6월 또는 9월 평가원 <b>3과목 등급합</b>.
     *
     * <p>규정이 <i>"6월 평가원 or 9월 평가원"</i>이라 <b>둘 중 좋은 쪽</b>을 본다 —
     * 나쁜 쪽을 쓰면 한 번 못 본 시험 때문에 장학이 날아간다.
     * 등급은 <b>작을수록 좋으므로</b> 합이 작은 쪽이 좋은 쪽이다.
     *
     * <p>⚠️ <b>대상 과목이 비어 있으면 판정하지 않는다.</b> 규정에 "3과목"이라고만 있어
     * 임의로 고르면 <b>엉뚱한 학생이 걸린다.</b>
     */
    private Detection detectGradeSum(StudentEnrollment enrollment, ScholarshipCancelRule rule) {
        List<String> subjects = rule.subjects();
        if (subjects.isEmpty()) {
            return null;
        }

        var submission = gradeRepository.findByEnrollmentId(enrollment.getId()).orElse(null);
        if (submission == null) {
            // 성적을 안 낸 학생은 판정 대상이 아니다. "미제출"과 "기준 미달"은 다르다
            return null;
        }

        Integer june = gradeSumOf(submission.activeScores(), ExamCode.JUNE, subjects);
        Integer sept = gradeSumOf(submission.activeScores(), ExamCode.SEPT, subjects);
        if (june == null && sept == null) {
            return null;
        }

        int best = Math.min(june == null ? Integer.MAX_VALUE : june,
                sept == null ? Integer.MAX_VALUE : sept);
        // 등급합이 기준을 "넘으면" 미충족이다 — 등급은 작을수록 좋다
        if (best <= rule.getThreshold()) {
            return null;
        }

        return new Detection(best,
                "%s 등급합 %d (기준 %d 이하). 6월 %s / 9월 %s 중 좋은 쪽"
                        .formatted(String.join("·", subjects), best, rule.getThreshold(),
                                june == null ? "미응시" : june.toString(),
                                sept == null ? "미응시" : sept.toString()));
    }

    /**
     * 그 회차의 대상 과목 등급합. <b>한 과목이라도 등급이 없으면 {@code null}</b>이다 —
     * 빠진 과목을 0으로 세면 합이 작아져 <b>미달인 학생이 통과</b>한다.
     */
    private Integer gradeSumOf(List<StudentExamScore> scores, ExamCode examCode,
                               List<String> subjects) {
        Set<String> targets = Set.copyOf(subjects);
        int sum = 0;
        int matched = 0;

        for (StudentExamScore score : scores) {
            if (score.getExamMaster().getExamCode() != examCode) {
                continue;
            }
            if (!targets.contains(score.getExamSubject().getSubjectCode())) {
                continue;
            }
            if (score.getGradeLevel() == null) {
                return null;
            }
            sum += score.getGradeLevel();
            matched++;
        }
        return matched == subjects.size() ? sum : null;
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

    /** @param value 걸린 실제 값. 검토 화면이 "왜 걸렸는지"를 보여주는 근거다 */
    private record Detection(int value, String detail) {
    }
}
