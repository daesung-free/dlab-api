package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.Scholarship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    @Query("""
            SELECT s FROM Scholarship s
            JOIN FETCH s.academy
            JOIN FETCH s.enrollment
            WHERE s.id = :id AND s.deleted = false
            """)
    Optional<Scholarship> findDetailById(Long id);
}
