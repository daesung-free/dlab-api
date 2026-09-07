package com.dlab.domain.audit.service;

import com.dlab.domain.audit.entity.ChangeLog;
import com.dlab.domain.audit.repository.ChangeLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 변경 이력 적재 (F-4.10-8).
 *
 * <p>대상 종류가 문자열이라 새 사용처가 생겨도 여기를 고칠 일이 없다 —
 * 부르는 쪽이 자기 {@code targetType}을 정해서 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class ChangeLogService {

    /** 계정에 붙은 역할 변경. */
    public static final String TARGET_ACCOUNT_ROLE = "ACCOUNT_ROLE";
    /** 계정 상태 변경(승인·탈퇴). */
    public static final String TARGET_ACCOUNT_STATUS = "ACCOUNT_STATUS";

    private final ChangeLogRepository changeLogRepository;

    /**
     * 이력 한 줄 남기기.
     *
     * <p><b>{@code REQUIRES_NEW}로 분리한다</b> — 이력 적재가 실패했다고 본 작업(권한
     * 변경)까지 롤백되면 안 된다. 반대로 본 작업이 롤백돼도 이력만 남는 경우가 생기는데,
     * "시도했다"는 기록이 남는 편이 아무 기록도 없는 것보다 낫다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long academyId, Short year, String targetType, Long targetId,
                       String targetLabel, String action, String before, String after) {
        changeLogRepository.save(new ChangeLog(academyId, year, targetType, targetId,
                targetLabel, action, before, after));
    }

    @Transactional(readOnly = true)
    public List<ChangeLog> historyOf(String targetType, Long targetId) {
        return changeLogRepository
                .findByTargetTypeAndTargetIdAndDeletedFalseOrderByCreatedAtDesc(targetType, targetId);
    }
}
