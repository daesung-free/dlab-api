package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.CommonCode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommonCodeRepository extends JpaRepository<CommonCode, Long> {

    /**
     * 그룹별 코드. 전 지점 공통({@code academy}가 null)과 해당 지점 것을 함께 준다.
     *
     * <p>휴일 조회와 같은 방식이다 — 지점 전용 항목이 생겨도 조회를 안 고쳐도 된다.
     */
    @Query("""
            SELECT c FROM CommonCode c
            WHERE c.grp = :grp
              AND (c.academy IS NULL OR c.academy.id = :academyId)
              AND c.active = true AND c.deleted = false
            ORDER BY c.sortOrder, c.code
            """)
    List<CommonCode> findByGroup(@Param("grp") String grp, @Param("academyId") Long academyId);

    /** 과목은 지점 구분이 없다(3.6이 acid를 받지 않는다). */
    @Query("""
            SELECT c FROM CommonCode c
            WHERE c.grp = :grp AND c.active = true AND c.deleted = false
            ORDER BY c.idx, c.sortOrder, c.code
            """)
    List<CommonCode> findAllOfGroup(@Param("grp") String grp);
}
