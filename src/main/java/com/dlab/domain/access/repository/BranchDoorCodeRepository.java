package com.dlab.domain.access.repository;

import com.dlab.domain.access.entity.BranchDoorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchDoorCodeRepository extends JpaRepository<BranchDoorCode, Long> {

    /**
     * 지금 번호 — 가장 최근에 교체한 것.
     *
     * <p>{@code applied_at} 이 같을 수 있어 {@code id} 를 함께 본다. 안 그러면 같은 초에
     * 두 번 교체했을 때 <b>어느 것이 지금인지 매번 달라진다.</b>
     */
    @Query("""
            SELECT c FROM BranchDoorCode c
            WHERE c.academy.id = :academyId AND c.deleted = false
            ORDER BY c.appliedAt DESC, c.id DESC
            LIMIT 1
            """)
    Optional<BranchDoorCode> findCurrent(@Param("academyId") Long academyId);

    /**
     * 그 지점에서 전에 쓴 번호인가.
     *
     * <p>★ {@code deleted} 를 조건에 넣지 않는다 — <b>지운 번호도 과거에 쓴 번호</b>라
     * 다시 쓰면 안 된다(요구사항 1-5 "교체한 이전 번호는 재사용 금지").
     */
    boolean existsByAcademyIdAndCodeHash(Long academyId, String codeHash);

    @Query("""
            SELECT c FROM BranchDoorCode c
            WHERE c.academy.id = :academyId AND c.deleted = false
            ORDER BY c.appliedAt DESC, c.id DESC
            """)
    List<BranchDoorCode> findHistory(@Param("academyId") Long academyId);
}
