package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.LectureCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LectureCategoryRepository extends JpaRepository<LectureCategory, Long> {

    /**
     * 그 지점에서 쓰는 유형 — <b>전 지점 공통 + 그 지점 것</b>을 합쳐서 준다.
     *
     * <p>지점 조건만 걸면 공통 유형이 빠져 드롭다운이 비고, 공통만 걸면 지점 고유
     * 항목이 안 보인다.
     *
     * @param activeOnly 등록 화면 드롭다운은 {@code true}(중지된 것은 못 고른다),
     *                   관리 화면은 {@code false}(중지된 것도 보여야 다시 켤 수 있다)
     */
    @Query("""
            SELECT c FROM LectureCategory c
            WHERE c.deleted = false
              AND c.year = :year
              AND (c.academy IS NULL OR c.academy.id = :academyId)
              AND (:activeOnly = false OR c.active = true)
            ORDER BY c.sortOrder, c.id
            """)
    List<LectureCategory> findUsable(@Param("academyId") Long academyId,
                                     @Param("year") short year,
                                     @Param("activeOnly") boolean activeOnly);

    @Query("SELECT c FROM LectureCategory c WHERE c.id = :id AND c.deleted = false")
    Optional<LectureCategory> findActiveById(@Param("id") Long id);
}
