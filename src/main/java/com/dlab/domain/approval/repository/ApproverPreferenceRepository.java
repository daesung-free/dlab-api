package com.dlab.domain.approval.repository;

import com.dlab.domain.approval.entity.ApproverPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApproverPreferenceRepository extends JpaRepository<ApproverPreference, Long> {

    /**
     * 현재 선택값 — <b>가장 최근 동의 행</b>.
     *
     * <p>덮어쓰지 않고 쌓는 구조라 "최신 한 건"이 곧 현재값이다.
     */
    @Query("""
            SELECT p FROM ApproverPreference p
            WHERE p.enrollment.id = :enrollmentId
              AND p.deleted = false
            ORDER BY p.agreedAt DESC, p.id DESC
            LIMIT 1
            """)
    Optional<ApproverPreference> findCurrent(@Param("enrollmentId") Long enrollmentId);

    /** 동의 이력 전체. "그때 무엇에 동의했나"에 답하는 화면용. */
    @Query("""
            SELECT p FROM ApproverPreference p
            LEFT JOIN FETCH p.terms
            WHERE p.enrollment.id = :enrollmentId
              AND p.deleted = false
            ORDER BY p.agreedAt DESC, p.id DESC
            """)
    List<ApproverPreference> findHistory(@Param("enrollmentId") Long enrollmentId);
}
