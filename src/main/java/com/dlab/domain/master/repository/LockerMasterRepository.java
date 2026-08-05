package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.LockerMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LockerMasterRepository extends JpaRepository<LockerMaster, Long> {

    @Query("""
            SELECT l FROM LockerMaster l
            LEFT JOIN FETCH l.assignedEnrollment e
            LEFT JOIN FETCH e.student
            WHERE l.academy.id = :academyId AND l.deleted = false
            ORDER BY l.lockerNo ASC
            """)
    List<LockerMaster> findByAcademyId(Long academyId);

    @Query("""
            SELECT l FROM LockerMaster l
            JOIN FETCH l.academy
            LEFT JOIN FETCH l.assignedEnrollment
            WHERE l.id = :id AND l.deleted = false
            """)
    Optional<LockerMaster> findDetailById(Long id);

    /** 이 학생이 이미 쓰는 사물함. 한 명이 여러 개 갖지 않도록 확인한다. */
    Optional<LockerMaster> findByAssignedEnrollmentIdAndDeletedFalse(Long enrollmentId);

    boolean existsByAcademyIdAndLockerNoAndDeletedFalse(Long academyId, String lockerNo);
}
