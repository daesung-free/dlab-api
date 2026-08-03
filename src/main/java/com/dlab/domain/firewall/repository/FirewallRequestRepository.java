package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.FirewallRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FirewallRequestRepository extends JpaRepository<FirewallRequest, Long> {

    Optional<FirewallRequest> findByApprovalRequestId(Long approvalRequestId);
}
