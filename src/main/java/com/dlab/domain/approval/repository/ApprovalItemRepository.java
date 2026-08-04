package com.dlab.domain.approval.repository;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalItemRepository extends JpaRepository<ApprovalItem, Long> {

    Optional<ApprovalItem> findByAcademyIdAndYearAndRequestType(Long academyId, short year, RequestType requestType);

    /** 해당 연도 승인 정책 전체. 전년도 복사가 쓴다. */
    List<ApprovalItem> findByAcademyIdAndYearAndDeletedFalse(Long academyId, short year);
}
