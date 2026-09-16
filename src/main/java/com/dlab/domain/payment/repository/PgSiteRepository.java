package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.PgChannel;
import com.dlab.domain.payment.entity.PgPurpose;
import com.dlab.domain.payment.entity.PgSite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PgSiteRepository extends JpaRepository<PgSite, Long> {

    /**
     * 쓸 사이트코드를 고른다.
     *
     * <p><b>지점별 행이 공용보다 우선한다.</b> 지금은 사업자별로만 발급됐지만 지점별로
     * 받게 될 수 있어(9/11 목록은 지점별이었다) 둘 다 있을 때 규칙이 필요하다.
     */
    @Query("""
            SELECT s FROM PgSite s
            WHERE (s.academy.id = :academyId OR s.academy IS NULL)
              AND s.purpose = :purpose
              AND s.channel = :channel
              AND s.active = true
              AND s.deleted = false
            ORDER BY CASE WHEN s.academy IS NULL THEN 1 ELSE 0 END
            """)
    List<PgSite> findUsable(Long academyId, PgPurpose purpose, PgChannel channel);

    default Optional<PgSite> findForUse(Long academyId, PgPurpose purpose, PgChannel channel) {
        return findUsable(academyId, purpose, channel).stream().findFirst();
    }

    @Query("""
            SELECT s FROM PgSite s
            WHERE s.deleted = false
            ORDER BY s.purpose, s.channel, s.id
            """)
    List<PgSite> findAllActive();

    /**
     * 같은 칸(지점·용도·채널)에 이미 등록된 행.
     *
     * <p>DB 에 유니크 제약이 있어 막히기는 하지만, 그대로 두면 화면에 제약 위반이 그대로
     * 올라간다 — 어느 행과 겹쳤는지 알려주려면 먼저 찾아봐야 한다.
     */
    @Query("""
            SELECT s FROM PgSite s
            WHERE s.purpose = :purpose
              AND s.channel = :channel
              AND s.deleted = false
              AND ((:academyId IS NULL AND s.academy IS NULL) OR s.academy.id = :academyId)
            """)
    Optional<PgSite> findByScope(Long academyId, PgPurpose purpose, PgChannel channel);
}
