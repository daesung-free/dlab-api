package com.dlab.domain.profile.repository;

import com.dlab.domain.profile.entity.StudentProfileForm;
import com.dlab.domain.user.entity.GradeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentProfileFormRepository extends JpaRepository<StudentProfileForm, Long> {

    /**
     * 그 연도의 양식 목록.
     *
     * <p>지점 양식과 <b>전 지점 공통({@code academy_id IS NULL})을 합쳐서</b> 본다 —
     * 공통을 빼면 I-17 확정 전(공통 한 행만 있는 상태)에 목록이 비어 보인다.
     */
    @Query("""
            SELECT f FROM StudentProfileForm f
            WHERE f.year = :year
              AND (f.academy IS NULL OR f.academy.id = :academyId)
              AND f.deleted = false
            ORDER BY f.gradeType ASC, f.version DESC
            """)
    List<StudentProfileForm> findByScope(@Param("academyId") Long academyId,
                                         @Param("year") short year);

    /**
     * 쓸 수 있는 양식 — 켜진 것 중 <b>가장 높은 버전</b>.
     *
     * <p>학년별 양식이 없으면 전 학년 공통({@code gradeType IS NULL})으로 떨어진다.
     * 지점 양식이 공통보다 앞서야 하므로 <b>정렬에 지점을 먼저 둔다</b> —
     * 그렇지 않으면 지점이 따로 만든 양식이 공통에 가려진다.
     */
    @Query("""
            SELECT f FROM StudentProfileForm f
            WHERE f.year = :year
              AND f.active = true
              AND (f.academy IS NULL OR f.academy.id = :academyId)
              AND (f.gradeType IS NULL OR f.gradeType = :gradeType)
              AND f.deleted = false
            ORDER BY CASE WHEN f.academy IS NULL THEN 1 ELSE 0 END ASC,
                     CASE WHEN f.gradeType IS NULL THEN 1 ELSE 0 END ASC,
                     f.version DESC
            """)
    List<StudentProfileForm> findUsable(@Param("academyId") Long academyId,
                                        @Param("year") short year,
                                        @Param("gradeType") GradeType gradeType);

    @Query("""
            SELECT f FROM StudentProfileForm f
            WHERE f.id = :id AND f.deleted = false
            """)
    Optional<StudentProfileForm> findActiveById(@Param("id") Long id);
}
