package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingStandard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BillingStandardRepository extends JpaRepository<BillingStandard, Long> {

    /**
     * 관리자 화면 목록 — 공통본 또는 그 지점 행만.
     *
     * <p>{@code itemType}·{@code active}는 화면 필터라 {@code null}이면 조건이 빠진다.
     *
     * <p><b>{@code sortOrder}가 먼저다.</b> {@code itemType}으로 먼저 정렬하면
     * enum이 문자열로 저장돼 <b>가나다순이 아니라 알파벳순</b>(LECTURE·MEAL·…·TUITION)이
     * 되어 교습비가 맨 뒤로 간다. 표시 순서는 사람이 정한다.
     */
    @Query("""
            SELECT s FROM BillingStandard s
            WHERE s.year = :year AND s.deleted = false
              AND (:academyId IS NULL AND s.academy IS NULL OR s.academy.id = :academyId)
              AND (:itemType IS NULL OR s.itemType = :itemType)
              AND (:active IS NULL OR s.active = :active)
            ORDER BY s.sortOrder, s.itemType, s.code
            """)
    List<BillingStandard> findAllByScope(@Param("year") short year,
                                         @Param("academyId") Long academyId,
                                         @Param("itemType") BillingItemType itemType,
                                         @Param("active") Boolean active);

    /** 코드 중복 확인. 지점 축까지 같아야 중복이다. */
    @Query("""
            SELECT s FROM BillingStandard s
            WHERE s.year = :year AND s.code = :code AND s.deleted = false
              AND (:academyId IS NULL AND s.academy IS NULL OR s.academy.id = :academyId)
            """)
    Optional<BillingStandard> findByCode(@Param("year") short year,
                                         @Param("code") String code,
                                         @Param("academyId") Long academyId);
}
