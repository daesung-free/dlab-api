package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.ScholarshipMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScholarshipMasterRepository extends JpaRepository<ScholarshipMaster, Long> {

    /**
     * 그 지점에서 쓸 수 있는 장학 종류.
     *
     * <p><b>지점 행이 있으면 그것만, 없으면 공통본</b>을 쓴다 — {@code code} 단위로 가른다.
     * 둘을 합치면 같은 장학이 두 번 나오고, 통째로 한쪽만 보면 지점 예외가 사라진다.
     */
    @Query("""
            SELECT m FROM ScholarshipMaster m
            WHERE m.year = :year AND m.deleted = false AND m.active = true
              AND (
                    m.academy.id = :academyId
                 OR (m.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM ScholarshipMaster o
                        WHERE o.year = :year AND o.code = m.code
                          AND o.academy.id = :academyId
                          AND o.deleted = false AND o.active = true))
              )
            ORDER BY m.sortOrder, m.code
            """)
    List<ScholarshipMaster> findApplicable(@Param("year") short year,
                                           @Param("academyId") Long academyId);

    /** 부여 시 코드 확인. 조회 축이 위와 같아야 "목록에 있는데 못 고르는" 일이 없다. */
    @Query("""
            SELECT m FROM ScholarshipMaster m
            WHERE m.year = :year AND m.code = :code
              AND m.deleted = false AND m.active = true
              AND (
                    m.academy.id = :academyId
                 OR (m.academy IS NULL AND NOT EXISTS (
                        SELECT 1 FROM ScholarshipMaster o
                        WHERE o.year = :year AND o.code = :code
                          AND o.academy.id = :academyId
                          AND o.deleted = false AND o.active = true))
              )
            """)
    Optional<ScholarshipMaster> findApplicableByCode(@Param("year") short year,
                                                     @Param("code") String code,
                                                     @Param("academyId") Long academyId);

    /** 관리자 화면 — 공통본 또는 그 지점 행만. 중지된 것도 나온다. */
    @Query("""
            SELECT m FROM ScholarshipMaster m
            WHERE m.year = :year AND m.deleted = false
              AND (:academyId IS NULL AND m.academy IS NULL OR m.academy.id = :academyId)
            ORDER BY m.sortOrder, m.code
            """)
    List<ScholarshipMaster> findAllByScope(@Param("year") short year,
                                           @Param("academyId") Long academyId);

    @Query("""
            SELECT m FROM ScholarshipMaster m
            WHERE m.year = :year AND m.code = :code AND m.deleted = false
              AND (:academyId IS NULL AND m.academy IS NULL OR m.academy.id = :academyId)
            """)
    Optional<ScholarshipMaster> findByCode(@Param("year") short year,
                                           @Param("code") String code,
                                           @Param("academyId") Long academyId);
}
