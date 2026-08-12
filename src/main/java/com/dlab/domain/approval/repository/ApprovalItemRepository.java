package com.dlab.domain.approval.repository;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalItemRepository extends JpaRepository<ApprovalItem, Long> {

    /**
     * ★ <b>삭제된 정책은 빼야 한다.</b> 안 빼면 관리자가 지운 정책이 계속 라우팅에 쓰여
     * "지웠는데 신청이 계속 통한다"가 된다.
     */
    Optional<ApprovalItem> findByAcademyIdAndYearAndRequestTypeAndDeletedFalse(
            Long academyId, short year, RequestType requestType);

    /** 해당 연도 승인 정책 전체. 전년도 복사가 쓴다. */
    List<ApprovalItem> findByAcademyIdAndYearAndDeletedFalse(Long academyId, short year);
}
