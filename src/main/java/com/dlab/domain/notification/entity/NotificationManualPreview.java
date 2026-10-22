package com.dlab.domain.notification.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 직원 수동 발송의 <b>미리보기 스냅샷</b>.
 *
 * <h2>★★ 발송은 이 행 하나만 받는다</h2>
 * 발송 요청에 {@code scope}·{@code enrollmentIds} 를 다시 싣게 하지 않는다 — 화면이
 * 미리보기 뒤에 대상을 바꿔도 서버는 모르고, <b>담당자가 본 것과 다른 사람에게 나간다.</b>
 * 그래서 대상·변수를 여기에 확정해 박아 두고 발송은 {@code previewId} 만 본다.
 *
 * <h2>★ 유효기간이 있다</h2>
 * 창을 열어 둔 사이 퇴원한 학생이 섞인다. 만료되면 다시 미리보기부터 한다 —
 * 서버가 조용히 갱신해 주면 「본 것과 다른 사람에게 나간다」가 그대로 돌아온다.
 *
 * <h2>⚠️ 1회성은 이 엔티티가 보장하지 않는다</h2>
 * 소비 플래그를 여기 두면 UPDATE 조건을 빠뜨렸을 때 조용히 두 번 통과한다.
 * {@link NotificationManualSend} 의 {@code UNIQUE (preview_id)} 가 막는다.
 */
@Getter
@Entity
@Table(name = "notification_manual_preview")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationManualPreview extends BaseEntity {

    /** 미리보기 유효기간. 창을 열어 둔 사이 명단이 바뀐다. */
    public static final java.time.Duration TTL = java.time.Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면에 주는 공개 식별자.
     *
     * <p>★ 연속 숫자 PK 를 그대로 주지 않는다 — 옆 번호를 넣어 보면 <b>남이 만든
     * 미리보기로 발송</b>이 가능해진다(대상도 문구도 내가 본 것이 아니다).
     */
    @Column(name = "preview_id", nullable = false, unique = true)
    private UUID previewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private NotificationTemplate template;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private ManualSendScope scope;

    /** {@link ManualSendScope#CLASS} 일 때만 값이 있다. */
    @Column(name = "class_id")
    private Long classId;

    /** 직원이 채운 변수(월·기간·날짜 등). 발송 시점에 이 값을 그대로 쓴다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> variables;

    /** 확정된 수신자 명단. <b>발송은 이것만 본다</b> — 조건을 다시 풀지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "targets", nullable = false, columnDefinition = "jsonb")
    private List<ManualSendTarget> targets;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public NotificationManualPreview(UUID previewId, NotificationTemplate template,
                                     Academy academy, short year, ManualSendScope scope,
                                     Long classId, Map<String, String> variables,
                                     List<ManualSendTarget> targets, Instant now) {
        this.previewId = previewId;
        this.template = template;
        this.academy = academy;
        this.year = year;
        this.scope = scope;
        this.classId = classId;
        this.variables = variables;
        this.targets = targets;
        this.targetCount = targets.size();
        this.expiresAt = now.plus(TTL);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
