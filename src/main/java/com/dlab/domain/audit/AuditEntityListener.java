package com.dlab.domain.audit;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link Audited} 엔티티의 변경을 감사 로그로 남긴다.
 *
 * <p><b>서비스마다 부르지 않는다.</b> 수동 호출로 두면 새 경로가 생길 때마다 빠뜨리고,
 * 감사 로그는 <b>나중에 붙여도 그 이전 기간을 복구할 수 없다</b>(CLAUDE.md §7).
 *
 * <h2>왜 "무엇이 바뀌었는지"까지는 안 남기나</h2>
 * JPA 라이프사이클 콜백은 <b>변경 전 값을 주지 않는다</b>. 전후 비교를 하려면
 * Hibernate 내부 이벤트(스냅샷)를 붙여야 하는데, 그건 세션 상태에 얹히는 작업이라
 * 잘못 건드리면 <b>저장 자체가 흔들린다</b>. 그래서 1차는 <b>"누가 언제 무엇을 건드렸나"</b>까지
 * 남기고, 전후값은 {@link AuditTrail}로 <b>필요한 곳에서 명시적으로</b> 기록한다.
 * (금액·점수처럼 다툼이 되는 값만 남기면 되고, 전 필드 스냅샷은 개인정보를 복제한다.)
 *
 * <h2>기록이 실패해도 원래 작업은 살린다</h2>
 * 감사 로그를 못 남겼다고 벌점 부여가 롤백되면 <b>운영이 멈춘다</b>. 예외는 삼키고
 * 로그로 남긴다 — 대신 그 로그가 비면 안 되므로 {@code error}로 찍는다.
 */
@Slf4j
public class AuditEntityListener {

    /** 스프링 빈이 아니라 JPA 가 만드는 객체라 정적으로 받아 둔다. */
    private static AuditRecorder recorder;

    static void register(AuditRecorder value) {
        recorder = value;
    }

    @PostPersist
    public void onCreate(Object entity) {
        record(entity, AuditAction.CREATE);
    }

    @PostUpdate
    public void onUpdate(Object entity) {
        // soft delete 를 UPDATE 로 남기면 화면에서 삭제를 구분할 수 없다
        boolean deleted = entity instanceof BaseEntity base && base.isDeleted();
        record(entity, deleted ? AuditAction.DELETE : AuditAction.UPDATE);
    }

    private void record(Object entity, AuditAction action) {
        if (recorder == null) {
            return;
        }
        Audited audited = entity.getClass().getAnnotation(Audited.class);
        if (audited == null) {
            return;
        }
        try {
            recorder.record(new AuditRecorder.Entry(
                    audited.value().isBlank() ? entity.getClass().getSimpleName() : audited.value(),
                    idOf(entity),
                    action,
                    academyIdOf(entity),
                    AuditContext.actorId(),
                    AuditContext.actorName(),
                    AuditContext.actorIp(),
                    null,
                    Instant.now(),
                    targetEnrollmentOf(entity)));
        } catch (Exception e) {
            // 감사 로그를 못 남겼다고 원래 작업을 되돌리면 운영이 멈춘다
            log.error("감사 로그 기록 실패: {} {}", entity.getClass().getSimpleName(), action, e);
        }
    }

    /**
     * 대상 학생(등록 건). 등록 건 자신이거나 {@code enrollment} 연관을 가진 엔티티만.
     *
     * <p>★ 연관은 필드로 읽지 않고 {@code getId()}를 부른다 — 지연 로딩 프록시는 필드가 비어 있어
     * 필드로 읽으면 {@code null}이 나온다. 프록시의 {@code getId()}는 초기화 없이 id 를 준다.
     */
    private Long targetEnrollmentOf(Object entity) {
        if (entity instanceof com.dlab.domain.user.entity.StudentEnrollment self) {
            return self.getId();
        }
        return read(entity, "enrollment") instanceof com.dlab.domain.user.entity.StudentEnrollment e
                ? e.getId() : null;
    }

    private Long idOf(Object entity) {
        return (Long) read(entity, "id");
    }

    /**
     * 지점 필터용.
     *
     * <p>엔티티마다 지점을 갖는 방식이 다르다 — {@code academy} 연관, {@code academyId} 컬럼,
     * 아예 없는 것(마스터). <b>없으면 {@code null}이고 그대로 남긴다</b> — 전 지점 공통
     * 마스터를 누가 고쳤는지도 기록되어야 한다.
     */
    private Long academyIdOf(Object entity) {
        Object direct = read(entity, "academyId");
        if (direct instanceof Long id) {
            return id;
        }
        Object academy = read(entity, "academy");
        return academy == null ? null : (Long) read(academy, "id");
    }

    private Object read(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getName().equals(fieldName)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 사람이 읽을 변경 목록. {@link AuditTrail}이 만든다. */
    public record Change(String field, String before, String after) {

        public static List<Change> of(String... fieldBeforeAfter) {
            List<Change> changes = new ArrayList<>();
            for (int i = 0; i + 2 < fieldBeforeAfter.length; i += 3) {
                changes.add(new Change(fieldBeforeAfter[i],
                        fieldBeforeAfter[i + 1], fieldBeforeAfter[i + 2]));
            }
            return changes;
        }
    }
}
