package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.DormAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DormAssignmentRepository extends JpaRepository<DormAssignment, Long> {

    /** 현재 사용 중인 배정. 퇴실 이력은 제외한다. */
    @Query("""
            SELECT a FROM DormAssignment a
              JOIN FETCH a.enrollment e JOIN FETCH e.student
            WHERE a.room.id = :roomId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    List<DormAssignment> findActiveByRoomId(Long roomId);

    @Query("""
            SELECT a FROM DormAssignment a JOIN FETCH a.room
            WHERE a.enrollment.id = :enrollmentId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    Optional<DormAssignment> findActiveByEnrollmentId(Long enrollmentId);

    /** 정원 확인용 현재 인원수. */
    @Query("""
            SELECT COUNT(a) FROM DormAssignment a
            WHERE a.room.id = :roomId AND a.releasedAt IS NULL AND a.deleted = false
            """)
    long countActiveByRoomId(Long roomId);
}
