package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.FirewallViolation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FirewallViolationRepository extends JpaRepository<FirewallViolation, Long> {

    /**
     * 그 학생의 위반 이력.
     *
     * <p><b>누적 기준 기간이 미확정이다</b>(연간/학기/영구, V1 주석). {@code enrollment}가
     * 이미 <b>연 단위 등록 건</b>이라 여기서 세면 자연히 그해 것만 잡힌다 — 기수가 바뀌면
     * 초기화된다. 기간 기준이 확정되면 이 메서드에 조건을 더한다.
     */
    List<FirewallViolation> findByEnrollmentIdAndDeletedFalseOrderByOccurredAtDesc(Long enrollmentId);
}
