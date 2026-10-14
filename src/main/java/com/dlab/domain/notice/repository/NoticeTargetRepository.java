package com.dlab.domain.notice.repository;

import com.dlab.domain.notice.entity.NoticeTarget;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeTargetRepository extends JpaRepository<NoticeTarget, Long> {

    @Query("""
            SELECT t FROM NoticeTarget t
            JOIN FETCH t.enrollment e
            JOIN FETCH e.student
            WHERE t.notice.id = :noticeId AND t.deleted = false
            ORDER BY t.id
            """)
    List<NoticeTarget> findByNotice(@Param("noticeId") Long noticeId);

    /**
     * 이미 대상인 학생 id 들.
     *
     * <p>★ <b>다시 보내도 행이 늘지 않게</b> 쓴다. 중복 행이 있으면
     * <b>「읽음 몇 명」 집계가 틀어진다.</b>
     */
    @Query("""
            SELECT t.enrollment.id FROM NoticeTarget t
            WHERE t.notice.id = :noticeId AND t.deleted = false
            """)
    List<Long> enrollmentIdsOf(@Param("noticeId") Long noticeId);

    long countByNoticeIdAndDeletedFalse(Long noticeId);
}
