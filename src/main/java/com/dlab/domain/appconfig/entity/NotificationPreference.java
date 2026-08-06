package com.dlab.domain.appconfig.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.user.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 수신 설정 (A-21 "알림 수신 동의·유형별 on/off").
 *
 * <p><b>행이 없으면 수신이다(opt-out).</b> 가입 시 전 이벤트 행을 만들어두는 방식은
 * 이벤트가 추가될 때마다 기존 계정 전체에 백필이 필요해지고, 빠뜨리면
 * <b>새 알림이 아무에게도 안 간다</b>.
 */
@Getter
@Entity
@Table(name = "notification_preference")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, length = 50)
    private NotificationEvent eventCode;

    @Column(nullable = false)
    private boolean enabled = true;

    public NotificationPreference(Account account, NotificationEvent eventCode, boolean enabled) {
        this.account = account;
        this.eventCode = eventCode;
        this.enabled = enabled;
    }

    public void change(boolean enabled) {
        this.enabled = enabled;
    }
}
