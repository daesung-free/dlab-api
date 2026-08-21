package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealVendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MealVendorRepository extends JpaRepository<MealVendor, Long> {

    /**
     * 운영 중인 업체.
     *
     * <p>내린 업체({@code active = false})도 <b>지우지 않는다</b> —
     * 과거 주문이 어느 업체 것이었는지가 정산 근거다.
     */
    @Query("""
            SELECT v FROM MealVendor v
            WHERE v.active = true AND v.deleted = false
            ORDER BY v.name
            """)
    List<MealVendor> findAllActive();

    boolean existsByNameAndDeletedFalse(String name);
}
