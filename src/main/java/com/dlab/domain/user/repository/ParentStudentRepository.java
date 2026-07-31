package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.ParentStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ParentStudentRepository extends JpaRepository<ParentStudent, Long> {

    List<ParentStudent> findByParentId(Long parentId);

    /** 해당 학생에 연결된 학부모 계정들. 알림 수신자를 찾을 때 쓴다. */
    @Query("""
            SELECT ps FROM ParentStudent ps
            JOIN FETCH ps.parent p
            JOIN FETCH p.userAccount
            WHERE ps.student.id = :studentId
            """)
    List<ParentStudent> findByStudentIdWithAccount(Long studentId);

    boolean existsByParentIdAndStudentId(Long parentId, Long studentId);
}
