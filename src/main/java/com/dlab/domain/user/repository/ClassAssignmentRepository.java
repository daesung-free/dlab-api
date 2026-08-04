package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ClassAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ClassAssignmentRepository extends JpaRepository<ClassAssignment, Long> {

    /**
     * 현재 활성 반 배정. 여기서 담임(= 승인 에스컬레이션 대상)이 자동으로 나온다.
     * 학생별 승인자 사전지정 화면을 만들지 않는 근거다.
     */
    @Query("""
            SELECT ca FROM ClassAssignment ca
            JOIN FETCH ca.classMaster cm
            LEFT JOIN FETCH cm.homeroomTeacher
            WHERE ca.enrollment.id = :enrollmentId
              AND ca.classType = com.dlab.domain.user.entity.ClassType.FIXED
              AND ca.active = true
            """)
    Optional<ClassAssignment> findActiveFixedByEnrollmentId(Long enrollmentId);

    /** 특정 반의 현재 배정 학생. 정원 확인·명단 조회에 쓴다. */
    @Query("""
            SELECT ca FROM ClassAssignment ca
            JOIN FETCH ca.enrollment e
            JOIN FETCH e.student
            WHERE ca.classMaster.id = :classId AND ca.active = true AND ca.deleted = false
            ORDER BY e.studentNo ASC
            """)
    List<ClassAssignment> findActiveByClassId(Long classId);

    /** 같은 유형의 기존 활성 배정. 새로 배정할 때 이전 것을 내리기 위해 찾는다. */
    /** 이 학생의 활성 배정 전체. 고정반·이동수업반이 따로 있어 여러 건이 나온다. */
    List<ClassAssignment> findByEnrollmentIdAndActiveTrue(Long enrollmentId);

    Optional<ClassAssignment> findByEnrollmentIdAndClassTypeAndActiveTrue(
            Long enrollmentId, com.dlab.domain.user.entity.ClassType classType);
}
