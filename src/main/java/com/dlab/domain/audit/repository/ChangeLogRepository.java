package com.dlab.domain.audit.repository;

import com.dlab.domain.audit.entity.ChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeLogRepository extends JpaRepository<ChangeLog, Long> {

    /** "이 대상의 이력" — 사용자 관리 화면의 권한 수정 이력이 이걸 쓴다. */
    List<ChangeLog> findByTargetTypeAndTargetIdAndDeletedFalseOrderByCreatedAtDesc(
            String targetType, Long targetId);
}
