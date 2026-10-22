package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabAttemptRepository extends JpaRepository<VocabAttempt, Long> {

    List<VocabAttempt> findByEnrollmentIdAndDeletedFalseOrderByStartedAtDesc(Long enrollmentId);

    List<VocabAttempt> findByRoundIdAndEnrollmentIdAndDeletedFalseOrderByStartedAtAsc(
            Long roundId, Long enrollmentId);

    /** 결과 조회(v1 7장) — 그 회차의 전 학생 응시. */
    List<VocabAttempt> findByRoundIdAndDeletedFalse(Long roundId);

    /**
     * 대표 점수용 — <b>최초</b> 제출 건이다(v1 Q5).
     *
     * <p>★ 최고점을 대표로 두면 <b>반복 응시로 점수를 올릴 수 있고</b>, 최근으로 두면
     * <b>연습 삼아 다시 푼 것이 기록을 덮는다.</b>
     */
    Optional<VocabAttempt> findFirstByRoundIdAndEnrollmentIdAndSubmittedAtNotNullAndDeletedFalseOrderBySubmittedAtAsc(
            Long roundId, Long enrollmentId);

    /** 진행 중인 응시 — ★ 하나만 열어 둔다. 여러 개면 어느 쪽이 그 회차 점수인지 모른다. */
    Optional<VocabAttempt> findFirstByRoundIdAndEnrollmentIdAndSubmittedAtIsNullAndDeletedFalseOrderByStartedAtDesc(
            Long roundId, Long enrollmentId);
}
