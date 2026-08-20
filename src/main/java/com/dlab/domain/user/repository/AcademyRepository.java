package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Academy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalTime;
import java.util.List;

public interface AcademyRepository extends JpaRepository<Academy, Long> {

    /**
     * 등원 기준시각이 [from, to) 구간에 걸린 활성 지점.
     * 미등원 감지 배치가 "이번 분에 마감된 지점"을 찾는 데 쓴다.
     */
    @Query("""
            SELECT a FROM Academy a
            WHERE a.active = true AND a.deleted = false
              AND a.attendanceDeadline >= :from
              AND a.attendanceDeadline < :to
            """)
    List<Academy> findActiveByDeadlineBetween(LocalTime from, LocalTime to);

    /**
     * 운영 중인 지점 목록.
     *
     * <p>가입 화면이 <b>인증 없이</b> 부른다 — 지점을 골라야 가입이 되는데 그 시점엔
     * 토큰이 없다. 지점명은 간판에 걸린 공개 정보라 노출해도 문제없지만,
     * <b>이 목록에 운영정보(키오스크 자격증명·PG 코드)를 실어 보내지 말 것.</b>
     */
    @Query("""
            SELECT a FROM Academy a
            WHERE a.active = true AND a.deleted = false
            ORDER BY a.acadCd
            """)
    List<Academy> findAllActive();
}
