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
     * <p><b>여러 명이 나올 수 있다.</b> 연락처는 부·모를 따로 보관하고,
     * DSA {@code getParentHpList}가 관계 코드와 함께 목록으로 내린다
     * (키오스크의 "부모에게 전화 걸기" 화면). "학부모 최대 1인"(I-12 0803)은
     * <b>앱 계정·승인 주체</b>에 대한 제약이지 연락처 개수 제한이 아니다.
     */
    @Query("""
            SELECT l FROM StudentGuardianLink l
            JOIN FETCH l.guardian g
            WHERE l.student.id = :studentId
              AND g.deleted = false
            ORDER BY l.relationOrder ASC
            """)
    List<StudentGuardianLink> findByStudentId(@Param("studentId") Long studentId);

    /**
     * 이 학생에 <b>앱 계정을 가진 학부모</b>가 이미 연결돼 있는가.
     *
     * <p>I-12(0803) "학부모 최대 1인"이 실제로 막아야 하는 것이 이것이다 — 승인 요청을
     * 누구에게 보낼지가 갈리면 안 된다. 반면 연락처용 보호자는 여러 명이어도 무방하다.
     */
    boolean existsByStudentIdAndApproverTrue(Long studentId);

    boolean existsByStudentIdAndGuardianId(Long studentId, Long guardianId);

    /**
     * 자녀 목록 — 앱의 자녀 전환 UI가 쓴다.
     *
     * <p><b>가장 최근 등록 건</b>은 호출자가 따로 붙인다. 사람(student)에 연결돼 있어서
     * 학번·지점·학년은 등록 건에 있는데, 재수·삼수로 등록 행이 여러 개일 수 있다.
     */
    @Query("""
            SELECT l FROM StudentGuardianLink l
            JOIN FETCH l.student
            WHERE l.guardian.id = :guardianId
            ORDER BY l.createdAt
            """)
    List<StudentGuardianLink> findChildrenOf(@Param("guardianId") Long guardianId);
}
