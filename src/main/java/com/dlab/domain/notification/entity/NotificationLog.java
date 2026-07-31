package com.dlab.domain.notification.entity;

import com.dlab.domain.user.entity.Branch;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.UserAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** 발송 이력. 재발송·디버깅과 중복발송 방지에 쓴다. */
@Getter
@Entity
@Table(name = "notification_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, length = 60)
    private NotificationEvent eventCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_account_id", nullable = false)
    private UserAccount recipient;

    /** 어떤 학생에 대한 알림인지. 다자녀 학부모 구분에 필요. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> variables;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * 중복 발송 방지 키(부분 유니크 인덱스). 예: 미등원 알림은 학생·날짜당 1건.
     * 배치가 여러 번 돌아도 같은 알림이 두 번 나가지 않는다.
     */
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public NotificationLog(Branch branch, NotificationEvent eventCode, NotificationChannel channel,
                           UserAccount recipient, Student student, String title, String body,
                           Map<String, String> variables, String dedupKey) {
        this.branch = branch;
        this.eventCode = eventCode;
        this.channel = channel;
        this.recipient = recipient;
        this.student = student;
        this.title = title;
        this.body = body;
        this.variables = variables;
        this.dedupKey = dedupKey;
        this.status = NotificationStatus.PENDING;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.failReason = reason;
    }

    public void markSkipped(String reason) {
        this.status = NotificationStatus.SKIPPED;
        this.failReason = reason;
    }
}
