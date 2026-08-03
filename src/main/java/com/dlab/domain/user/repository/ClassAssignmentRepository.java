package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ClassAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ClassAssignmentRepository extends JpaRepository<ClassAssignment, Long> {

    /**
     * 현재 활성 반 배정. 여기서 담임(= 승인 에스컬레이션 대상)이 자동으로 나온다.
     * 학생별 승인자 사전지정 화면을 만들지 않는 근거다.
     */
    @Query("""
            SELECT ca FROM ClassAssignment ca
            JOIN FETCH ca.classMaster cm
            LEFT JOIN FETCH cm.homeroomEmployee
            WHERE ca.enrollment.id = :enrollmentId
              AND ca.classType = com.dlab.domain.user.entity.ClassType.FIXED
              AND ca.active = true
            """)
    Optional<ClassAssignment> findActiveFixedByEnrollmentId(Long enrollmentId);
}
