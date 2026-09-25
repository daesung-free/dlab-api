package com.dlab.api.admin.audit;

import com.dlab.common.config.SecurityAuditorAware;
import com.dlab.domain.audit.AuditAction;
import com.dlab.domain.audit.AuditLog;
import com.dlab.domain.audit.AuditLogRepository;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 금일 수정 이력 = 감사 로그 (F-C-1).
 *
 * <p>DSA 기능확인 회신에서 클라이언트가 <b>"사용"에 체크한 항목</b>이다
 * (메모/기타 9건 중 이것만).
 *
 * <p><b>{@code created_by}로는 답이 안 된다.</b> 그건 최초 작성자만 남는다 —
 * "누가 이 학생 벌점을 지웠나"는 변경 시점마다 행이 있어야 답할 수 있다.
 */
@Tag(name = "관리자 · 수정 이력 (F-C-1)")
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminAuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;
    private final com.dlab.domain.user.repository.StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 수정 이력.
     *
     * <p>기본이 <b>오늘</b>이다 — 화면 이름이 "금일 수정 이력"이고, 기간을 안 주면
     * 전 기간을 훑게 되어 화면이 안 뜬다.
     *
     * <p><b>지점을 모르는 로그도 함께 보인다.</b> 전 지점 공통 마스터를 누가 고쳤는지도
     * 기록되어야 하는데, 지점 필터에 걸려 빠지면 그 기록이 어디에도 안 남는다.
     *
     * @param academyId  비우면 내 지점. 전 지점 권한자가 비우면 전 지점이다
     * @param entityType {@code 상벌점}·{@code 성적}처럼 화면에 보이는 이름 그대로다
     * @param action     {@code CREATE}·{@code UPDATE}·{@code DELETE}. 비우면 전부다
     */
    @GetMapping
    public ApiResponse<List<AuditLogResponse>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) AuditAction action,
            @PageableDefault(size = 50) Pageable pageable) {

        LocalDate today = LocalDate.now(clock);
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? today : to;

        var page = auditLogRepository.search(
                me.resolveAcademyScope(academyId), entityType, actorId, action,
                start.atStartOfDay(clock.getZone()).toInstant(),
                // 끝 날짜를 포함해야 한다 — 오늘 수정분이 오늘 조회에서 빠지면 안 된다
                end.plusDays(1).atStartOfDay(clock.getZone()).toInstant(),
                pageable);

        var names = actorNames(page.getContent());
        var targets = targets(page.getContent());
        return ApiResponse.from(page.map(a -> AuditLogResponse.of(a, names, targets)));
    }

    /** 한 건이 어떻게 바뀌어 왔나 — 학생 상세에서 "이 기록의 이력". */
    @GetMapping("/entities/{entityType}/{entityId}")
    public ApiResponse<List<AuditLogResponse>> history(@PathVariable String entityType,
                                                       @PathVariable Long entityId) {
        var logs = auditLogRepository.findByEntity(entityType, entityId);
        var names = actorNames(logs);
        var targets = targets(logs);
        return ApiResponse.success(logs.stream()
                .map(a -> AuditLogResponse.of(a, names, targets)).toList());
    }

    /**
     * 계정 id → 사람 이름.
     *
     * <p><b>기록 시점이 아니라 조회 시점에 붙인다.</b> 저장할 때 조회하면 JPA 리스너가
     * 플러시 중이라 {@code ConcurrentModificationException} 이 난다 — 실제로 그렇게 만들었다가
     * 커밋이 통째로 깨졌다.
     *
     * <p>계정마다 따로 조회하면 50줄짜리 화면에 쿼리가 50번 나가므로 <b>한 번에 받아 붙인다.</b>
     */
    private java.util.Map<Long, String> actorNames(java.util.List<AuditLog> logs) {
        java.util.Set<Long> ids = logs.stream()
                .map(AuditLog::getActorId)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.equals(SecurityAuditorAware.SYSTEM_ACCOUNT_ID))
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (Object[] row : accountRepository.findActorNames(ids)) {
            if (row[1] != null) {
                names.put((Long) row[0], (String) row[1]);
            }
        }
        return names;
    }

    /**
     * 대상 학생(등록 건 id → 등록 건). 이름·학번을 붙이려고 <b>한 번에</b> 가져온다.
     *
     * <p>이름은 이력에 저장하지 않는다 — 개인정보가 이력으로 복제되기 때문이다.
     */
    private java.util.Map<Long, com.dlab.domain.user.entity.StudentEnrollment> targets(
            java.util.List<AuditLog> logs) {
        java.util.Set<Long> ids = logs.stream()
                .map(AuditLog::getTargetEnrollmentId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return enrollmentRepository.findWithStudentByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.dlab.domain.user.entity.StudentEnrollment::getId, e -> e));
    }

    /**
     * @param changes 바뀐 필드만 담긴 JSON. <b>대부분 비어 있다</b> — 전 필드 스냅샷을
     *                뜨면 개인정보가 이력으로 복제되므로, 금액·점수처럼 다툼이 될 수 있는
     *                값만 명시적으로 남긴다
     * @param actorId {@code 0}이면 배치·스케줄러다
     */
    public record AuditLogResponse(Long id, String entityType, Long entityId, AuditAction action,
                                   Long academyId, Long actorId, String actorName, String actorIp,
                                   String changes, Instant occurredAt,
                                   Long targetEnrollmentId, String targetStudentName,
                                   String targetStudentNo) {

        /**
         * @param names 조회 시점에 붙인 사람 이름. 없으면 저장된 값을 그대로 쓴다 —
         *              계정이 완전히 사라진 옛 기록이 빈칸이 되면 안 된다
         */
        static AuditLogResponse of(AuditLog a, java.util.Map<Long, String> names,
                                   java.util.Map<Long, com.dlab.domain.user.entity.StudentEnrollment> targets) {
            var target = a.getTargetEnrollmentId() == null ? null
                    : targets.get(a.getTargetEnrollmentId());
            String actorName = SecurityAuditorAware.SYSTEM_ACCOUNT_ID.equals(a.getActorId())
                    ? "시스템"
                    : names.getOrDefault(a.getActorId(), a.getActorName());
            return new AuditLogResponse(a.getId(), a.getEntityType(), a.getEntityId(),
                    a.getAction(), a.getAcademyId(), a.getActorId(), actorName,
                    a.getActorIp(), a.getChanges(), a.getOccurredAt(),
                    a.getTargetEnrollmentId(),
                    target == null ? null : target.getStudent().getName(),
                    target == null ? null : target.getStudentNo());
        }
    }
}
