package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentGuardianLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StudentGuardianLinkRepository
        extends JpaRepository<StudentGuardianLink, StudentGuardianLink.Key> {

    /** 학생당 학부모는 최대 1인(I-12 0803). 이미 붙어 있으면 다른 학부모가 연결할 수 없다. */
    boolean existsByStudentId(Long studentId);

    boolean existsByStudentIdAndGuardianId(Long studentId, Long guardianId);

    /**
     * 자녀 목록 — 앱의 자녀 전환 UI가 쓴다.
     *
     * <p><b>가장 최근 등록 건</b>을 함께 준다. 사람(student)에 연결돼 있어서 학번·지점·학년은
     * 등록 건에 있는데, 재수·삼수로 등록 행이 여러 개일 수 있다.
     */
    @Query("""
            SELECT l FROM StudentGuardianLink l
            JOIN FETCH l.student
            WHERE l.guardian.id = :guardianId
            ORDER BY l.createdAt
            """)
    List<StudentGuardianLink> findChildrenOf(Long guardianId);
}
