package com.dlab.domain.kiosk.repository;

import com.dlab.domain.kiosk.entity.BranchConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchConfigRepository extends JpaRepository<BranchConfig, Long> {

    /** {@code /auth/token} 검증 경로. client_id로 지점을 역조회한다. */
    Optional<BranchConfig> findByKioskClientIdAndDeletedFalse(String kioskClientId);

    Optional<BranchConfig> findByAcademyIdAndDeletedFalse(Long academyId);
}
