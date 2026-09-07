package com.dlab.domain.notice.repository;

import com.dlab.domain.notice.entity.NoticeRead;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeReadRepository extends JpaRepository<NoticeRead, Long> {

    boolean existsByNoticeIdAndEnrollmentId(Long noticeId, Long enrollmentId);

    /**
     * 공지별 열람 수를 <b>한 번에</b>.
     *
     * <p>목록 화면이 공지마다 세면 쿼리가 행 수만큼 나간다.
     */
    @Query("""
            SELECT r.notice.id, COUNT(r)
            FROM NoticeRead r
            WHERE r.notice.id IN :noticeIds AND r.deleted = false
            GROUP BY r.notice.id
            """)
    List<Object[]> countByNoticeIds(@Param("noticeIds") Collection<Long> noticeIds);
}
