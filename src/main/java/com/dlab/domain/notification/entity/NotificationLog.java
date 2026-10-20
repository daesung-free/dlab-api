package com.dlab.domain.notification.entity;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Student;
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
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year")
    private Short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, length = 60)
    private NotificationEvent eventCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    /**
     * 수신자 계정. <b>계정이 없는 수신자는 비어 있다</b> — 아래 {@link #recipientPhone} 참고.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_account_id")
    private Account recipient;

    /**
     * 계정 없는 수신자의 번호.
     *
     * <h2>★ 계정이 없는 수신자가 둘 있고 둘 다 확정된 요구다</h2>
     * <ul>
     *   <li><b>입학 대기자</b>(디랩 1019, 7번 대기자 안내톡) — {@code admission_reservation}
     *       은 학생이 아니라 계정도 {@code student} 행도 없고 번호만 들고 있다</li>
     *   <li><b>급식업체</b>(0826 확정 — 환불정보를 업체에 알림톡으로 즉시 발송) —
     *       사업자라 계정이 없다</li>
     * </ul>
     *
     * <p>★ <b>전용 발송 경로를 따로 만들지 않는다.</b> 그러면 발송 이력이 두 곳으로 갈라져
     * <b>「왜 안 갔나」에 답할 수 없다.</b>
     *
     * <p>⚠️ 계정과 번호 중 <b>하나는 반드시 있어야 한다</b>({@code ck_notification_recipient}).
     * 둘 다 비면 「누구에게 보내려 했는지」를 잃는다.
     */
    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    /** 계정 없는 수신자의 표시 이름. 이력 화면이 「누구에게 갔나」를 보여주는 데 쓴다. */
    @Column(name = "recipient_name", length = 50)
    private String recipientName;

    /** 사람(student)을 가리킨다 — 알림은 "누구 얘기인지"가 중요하지 기수가 중요하지 않다. */
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

    /** 중복 발송 방지 키(부분 유니크 인덱스). 배치 재실행·다중 인스턴스에도 안전하다. */
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public NotificationLog(Academy academy, Short year, NotificationEvent eventCode,
                           NotificationChannel channel, Account recipient, Student student,
                           String title, String body, Map<String, String> variables, String dedupKey) {
        this.academy = academy;
        this.year = year;
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

    /**
     * 계정 없는 수신자에게 보내는 이력.
     *
     * <p>★ {@code student} 를 받는다 — 대기자는 학생 행이 없어 {@code null} 이지만,
     * 급식업체 발송처럼 <b>어느 학생 건인지가 있는 경우</b>는 남겨야 한다.
     */
    public static NotificationLog toPhone(Academy academy, Short year,
                                          NotificationEvent eventCode,
                                          NotificationChannel channel,
                                          String phone, String name, Student student,
                                          String title, String body,
                                          Map<String, String> variables, String dedupKey) {
        NotificationLog log = new NotificationLog(academy, year, eventCode, channel,
                null, student, title, body, variables, dedupKey);
        log.recipientPhone = phone;
        log.recipientName = name;
        return log;
    }

    /**
     * 실제로 보낼 번호 — <b>발송 구현체는 이걸로 읽는다.</b>
     *
     * <p>계정이 있으면 그 사람의 번호, 없으면 {@link #recipientPhone} 이다.
     * 발송기가 계정에서만 번호를 꺼내면 <b>번호로 보내는 이력이 조용히 실패</b>한다.
     */
    public String resolvedPhone() {
        if (recipientPhone != null && !recipientPhone.isBlank()) {
            return recipientPhone;
        }
        return recipient == null ? null : phoneOf(recipient);
    }

    private static String phoneOf(Account account) {
        if (account.getStudent() != null) {
            return account.getStudent().getPhone();
        }
        if (account.getGuardian() != null) {
            return account.getGuardian().getPhone();
        }
        if (account.getEmployee() != null) {
            return account.getEmployee().getPhone();
        }
        if (account.getTeacher() != null) {
            return account.getTeacher().getPhone();
        }
        return null;
    }

    /** 이력 화면 표시용 — 계정이 있으면 그 사람 이름, 없으면 저장된 이름. */
    public String resolvedRecipientName() {
        if (recipient == null) {
            return recipientName;
        }
        if (recipient.getStudent() != null) {
            return recipient.getStudent().getName();
        }
        if (recipient.getGuardian() != null) {
            return recipient.getGuardian().getName();
        }
        if (recipient.getEmployee() != null) {
            return recipient.getEmployee().getName();
        }
        if (recipient.getTeacher() != null) {
            return recipient.getTeacher().getName();
        }
        return recipientName;
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
