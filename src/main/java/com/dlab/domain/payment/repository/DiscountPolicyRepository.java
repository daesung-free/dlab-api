package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.DiscountPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiscountPolicyRepository extends JpaRepository<DiscountPolicy, Long> {

    /**
     * 그 지점에서 쓸 수 있는 정책.
     *
     * <p>★ <b>지점 전용과 전 지점 공통을 함께 본다</b> — 공휴일({@code holiday.academy_id})과
     * 같은 규약이다. 한쪽만 보면 공통 할인이 지점 화면에서 사라진다.
     *
     * <p>⚠️ {@code active} 를 여기서 걸지 않는다. 관리자 화면은 꺼진 것도 보여야
     * <b>「행이 없다」와 「꺼져 있다」를 구분</b>할 수 있다. 적용 경로가 따로 켜진 것만 쓴다.
     */
    @Query("""
            select p from DiscountPolicy p
             where p.deleted = false
               and p.year = :year
               and (p.academy is null or p.academy.id = :academyId)
             order by p.code, p.applyFromMonth nulls first
            """)
    List<DiscountPolicy> findForAcademy(@Param("year") Short year,
                                        @Param("academyId") Long academyId);

    Optional<DiscountPolicy> findByIdAndDeletedFalse(Long id);

    @Query("""
            select p from DiscountPolicy p
             where p.deleted = false and p.active = true
               and p.year = :year
               and (p.academy is null or p.academy.id = :academyId)
               and p.code = :code
            """)
    List<DiscountPolicy> findActiveByCode(@Param("year") Short year,
                                          @Param("academyId") Long academyId,
                                          @Param("code") String code);
}
