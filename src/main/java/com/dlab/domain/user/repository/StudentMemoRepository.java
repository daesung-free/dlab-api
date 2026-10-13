package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentMemo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentMemoRepository extends JpaRepository<StudentMemo, Long> {

    /** 그 학생 메모. <b>최근 것이 위</b>다 — 목록이 늘 이 순서로 나간다. */
    @Query("""
            SELECT m FROM StudentMemo m
            WHERE m.enrollment.id = :enrollmentId AND m.deleted = false
            ORDER BY m.id DESC
            """)
    List<StudentMemo> findByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /**
     * 여러 학생의 <b>메모 개수</b> — 목록이 「메모 있음」 표시에 쓴다.
     *
     * <p>★ 내용까지 받지 않는다. 목록에 메모 본문을 실으면 <b>응답이 급격히 커지고</b>
     * 정작 화면은 개수만 쓴다.
     */
    @Query("""
            SELECT m.enrollment.id, COUNT(m) FROM StudentMemo m
            WHERE m.enrollment.id IN :enrollmentIds AND m.deleted = false
            GROUP BY m.enrollment.id
            """)
    List<Object[]> countByEnrollments(
            @Param("enrollmentIds") java.util.Collection<Long> enrollmentIds);
}
