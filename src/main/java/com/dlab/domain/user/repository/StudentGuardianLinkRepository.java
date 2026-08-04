package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentGuardianLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentGuardianLinkRepository
        extends JpaRepository<StudentGuardianLink, StudentGuardianLink.Key> {

    /**
     * 학생에 연결된 보호자. <b>등록 건이 아니라 사람(student)에 붙는다</b> —
     * 자녀가 재등록해도 연결이 유지돼야 하기 때문이다.
     *
     * <p>0803 시트에서 학부모는 최대 1인으로 확정됐지만 목록으로 돌려준다.
     * 스키마가 다건을 허용하고 있어(연결 테이블) 실제로 2건이 들어와도
     * 조회가 조용히 하나만 보여주면 데이터 이상을 못 잡는다.
     */
    @Query("""
            SELECT l FROM StudentGuardianLink l
            JOIN FETCH l.guardian g
            WHERE l.student.id = :studentId
              AND g.deleted = false
            ORDER BY l.relationOrder ASC
            """)
    List<StudentGuardianLink> findByStudentId(@Param("studentId") Long studentId);
}
