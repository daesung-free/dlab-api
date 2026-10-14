package com.dlab.domain.profile.repository;

import com.dlab.domain.profile.entity.StudentProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

    /** 그 등록 건의 신상기록부. 등록 건마다 한 장이다. */
    @Query("""
            SELECT p FROM StudentProfile p
            JOIN FETCH p.enrollment e
            JOIN FETCH e.student
            WHERE p.enrollment.id = :enrollmentId AND p.deleted = false
            """)
    Optional<StudentProfile> findByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /**
     * 작성된 등록 건 id — 상담 현황의 「신상기록부 작성 여부」가 쓴다.
     *
     * <p>★ <b>학생 수만큼 조회하지 않는다.</b> 상담 현황은 재원생 전원이 대상이라
     * 한 줄씩 물어보면 수백 번 쿼리가 나간다.
     */
    @Query("""
            SELECT p.enrollment.id FROM StudentProfile p
            WHERE p.academy.id = :academyId AND p.year = :year AND p.deleted = false
            """)
    List<Long> findWrittenEnrollmentIds(@Param("academyId") Long academyId,
                                        @Param("year") short year);
}
