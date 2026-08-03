package com.dlab.domain.approval.repository;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApprovalItemRepository extends JpaRepository<ApprovalItem, Long> {

    Optional<ApprovalItem> findByAcademyIdAndYearAndRequestType(Long academyId, short year, RequestType requestType);
}
