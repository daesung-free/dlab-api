package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.Scholarship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScholarshipRepository extends JpaRepository<Scholarship, Long> {

    @Query("""
            SELECT s FROM Scholarship s
            JOIN FETCH s.enrollment e
            JOIN FETCH e.student
            WHERE s.enrollment.id = :enrollmentId AND s.deleted = false
            """)
    List<Scholarship> findByEnrollmentId(Long enrollmentId);

    /**
     * 여러 학생의 장학을 <b>한 번에</b> 가져온다. 목록 화면용이라 {@code enrollment}·
     * {@code student}를 fetch join 하지 않는다 — 호출부가 이미 등록 건을 들고 있고,
     * 붙이면 같은 학생 행이 중복으로 늘어난다.
     */
    @Query("""
            SELECT s FROM Scholarship s
            WHERE s.enrollment.id IN :enrollmentIds AND s.deleted = false
            ORDER BY s.id ASC
            """)
    List<Scholarship> findByEnrollmentIds(Collection<Long> enrollmentIds);

    @Query("""
            SELECT s FROM Scholarship s
            JOIN FETCH s.academy
            JOIN FETCH s.enrollment
            WHERE s.id = :id AND s.deleted = false
            """)
    Optional<Scholarship> findDetailById(Long id);
}
