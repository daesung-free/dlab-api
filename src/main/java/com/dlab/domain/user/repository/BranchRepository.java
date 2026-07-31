package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalTime;
import java.util.List;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    /**
     * 등원 기준시각이 [from, to) 구간에 걸린 활성 지점.
     * 미등원 감지 배치가 "이번 분에 마감된 지점"을 찾는 데 쓴다.
     */
    @Query("""
            SELECT b FROM Branch b
            WHERE b.active = true
              AND b.attendanceDeadline >= :from
              AND b.attendanceDeadline < :to
            """)
    List<Branch> findActiveByDeadlineBetween(LocalTime from, LocalTime to);
}
