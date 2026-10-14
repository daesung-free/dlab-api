package com.dlab.domain.notice.repository;

import com.dlab.domain.notice.entity.Notice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /**
     * 관리자 목록.
     *
     * <p><b>전 지점 공지(academy_id IS NULL)를 함께 내린다.</b> 지점 관리자도 본사 공지를
     * 봐야 한다 — 조회는 전체 공유이고 제한되는 건 작성뿐이다.
     */
    @Query("""
            SELECT n FROM Notice n
            LEFT JOIN FETCH n.classMaster
            WHERE n.year = :year
              AND (n.academy IS NULL OR n.academy.id = :academyId)
              AND n.deleted = false
            ORDER BY n.pinned DESC, n.createdAt DESC
            """)
    List<Notice> findForAdmin(@Param("academyId") Long academyId, @Param("year") short year);

    /** 전 지점 권한자 — 지점 필터 없이 전부. */
    @Query("""
            SELECT n FROM Notice n
            LEFT JOIN FETCH n.classMaster
            WHERE n.year = :year
              AND n.deleted = false
            ORDER BY n.pinned DESC, n.createdAt DESC
            """)
    List<Notice> findForAdminAllAcademy(@Param("year") short year);

    /**
     * 앱 피드 후보.
     *
     * <p>전 지점 + 내 지점 + 내 반 + 나 개인을 한 번에 가져온다. <b>공개 시각 판정은
     * 여기서 하지 않는다</b> — 서비스가 {@code Clock}으로 거른다.
     *
     * @param classId      배정된 반. 없으면 {@code null}
     * @param enrollmentId 본인 등록 건
     *
     * <p>★ <b>{@code SELECTED} 는 대상자가 공지가 아니라 {@code notice_target} 에 있다.</b>
     * {@code EXISTS} 로 본다 — {@code JOIN} 하면 대상자 수만큼 <b>같은 공지가 여러 줄</b>로 나온다.
     *
     * <p>⚠️ <b>이 쿼리 안에 {@code --} 주석을 쓰지 말 것.</b> JPQL 은 그 문법을 모르고,
     * 깨지면 <b>리포지토리 빈이 안 떠서 그 컨텍스트를 쓰는 테스트가 통째로 실패</b>한다 —
     * 원인이 쿼리라는 것이 한눈에 안 보인다.
     */
    @Query("""
            SELECT n FROM Notice n
            WHERE n.year = :year
              AND n.deleted = false
              AND (
                    n.scope = com.dlab.domain.notice.entity.NoticeScope.ALL
                 OR (n.scope = com.dlab.domain.notice.entity.NoticeScope.BRANCH
                     AND n.academy.id = :academyId)
                 OR (n.scope = com.dlab.domain.notice.entity.NoticeScope.CLASS
                     AND n.classMaster.id = :classId)
                 OR (n.scope = com.dlab.domain.notice.entity.NoticeScope.INDIVIDUAL
                     AND n.enrollment.id = :enrollmentId)
                 OR (n.scope = com.dlab.domain.notice.entity.NoticeScope.SELECTED
                     AND EXISTS (SELECT 1 FROM NoticeTarget t
                                  WHERE t.notice = n
                                    AND t.enrollment.id = :enrollmentId
                                    AND t.deleted = false))
              )
            ORDER BY n.pinned DESC, n.createdAt DESC
            """)
    List<Notice> findFeed(@Param("academyId") Long academyId,
                          @Param("classId") Long classId,
                          @Param("enrollmentId") Long enrollmentId,
                          @Param("year") short year);
}
