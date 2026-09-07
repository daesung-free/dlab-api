package com.dlab.domain.audit;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 저장.
 *
 * <p><b>{@code REQUIRES_NEW}로 분리한다.</b> 원래 작업과 같은 트랜잭션에 두면
 * 감사 기록이 실패했을 때 <b>벌점 부여나 출결 정정까지 함께 롤백된다</b> —
 * 상벌점 규칙엔진에서 한 판단과 같다.
 *
 * <p>반대 방향도 막는다: 원래 작업이 나중에 롤백돼도 감사 로그는 남는다.
 * "시도했다"는 사실이 남는 편이 추적에 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRecorder {

    private final AuditLogRepository auditLogRepository;

    /** JPA 가 만드는 리스너에 자신을 넘긴다 — 리스너는 스프링 빈이 아니다. */
    @PostConstruct
    void wire() {
        AuditEntityListener.register(this);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Entry entry) {
        auditLogRepository.save(new AuditLog(
                entry.entityType(), entry.entityId(), entry.action(), entry.academyId(),
                entry.actorId(), entry.actorName(), entry.actorIp(),
                entry.changes(), entry.occurredAt()));
    }

    /**
     * 전후값을 함께 남긴다 — 금액·점수처럼 <b>다툼이 될 수 있는 값</b>에만 쓴다.
     *
     * <p>전 필드 스냅샷을 뜨지 않는 이유는 개인정보가 이력 테이블로 통째 복제되고,
     * 지운 값이 여기 영구히 남기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChanges(String entityType, Long entityId, Long academyId,
                              List<AuditEntityListener.Change> changes) {
        auditLogRepository.save(new AuditLog(
                entityType, entityId, AuditAction.UPDATE, academyId,
                AuditContext.actorId(), AuditContext.actorName(), AuditContext.actorIp(),
                serialize(changes), Instant.now()));
    }

    /** JSON 한 줄. 라이브러리를 태우지 않는 이유는 값이 단순해서다. */
    private static String serialize(List<AuditEntityListener.Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < changes.size(); i++) {
            var c = changes.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"field\":").append(quote(c.field()))
                    .append(",\"before\":").append(quote(c.before()))
                    .append(",\"after\":").append(quote(c.after()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + '"';
    }

    public record Entry(String entityType, Long entityId, AuditAction action, Long academyId,
                        Long actorId, String actorName, String actorIp,
                        String changes, Instant occurredAt) {
    }
}
