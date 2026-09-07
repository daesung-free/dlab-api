package com.dlab.domain.notification.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 이벤트별 발송 채널과 문구 템플릿.
 *
 * <p>문구는 아직 운영팀과 확정되지 않은 블로커(I-4)라, 지금은 채널 매핑과 변수 슬롯만
 * 확정하고 문구는 비운 채 {@code contentConfirmed = false}로 둔다. 문구가 미확정인
 * 템플릿은 이력만 남기고 실제 발송은 하지 않는다.
 *
 * <p>academyId/year가 없는 것은 의도된 예외다 — 템플릿은 전 지점 공통 참조값이다.
 */
@Getter
@Entity
@Table(name = "notification_template")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate extends BaseEntity {

    /** 모든 학생 관련 알림에 반드시 들어가야 하는 변수. 다자녀 학부모 구분용. */
    public static final String REQUIRED_STUDENT_NAME = "studentName";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_code", nullable = false, unique = true, length = 60)
    private NotificationEvent eventCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "title_template", nullable = false, length = 200)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "text")
    private String bodyTemplate;

    /** 쉼표로 구분된 변수명 목록. */
    @Column(name = "required_variables", nullable = false, columnDefinition = "text")
    private String requiredVariables;

    @Column(name = "kakao_template_code", length = 60)
    private String kakaoTemplateCode;

    /** 운영팀 문구 확정 여부(I-4). 심사 상태와는 <b>다른 축</b>이다. */
    @Column(name = "content_confirmed", nullable = false)
    private boolean contentConfirmed;

    @Column(nullable = false)
    private boolean active;

    /** A-C3 매핑표의 수신자 축. */
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 20)
    private RecipientType recipientType = RecipientType.PARENT;

    /** 카카오 알림톡 사전심사 상태(E-5). FCM은 {@code NOT_REQUIRED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ReviewStatus reviewStatus = ReviewStatus.NOT_REQUIRED;

    /** 반려 사유. 없으면 무엇을 고쳐야 할지 알 수 없다. */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_at")
    private java.time.Instant reviewedAt;

    public NotificationTemplate(NotificationEvent eventCode, NotificationChannel channel,
                                RecipientType recipientType, String requiredVariables) {
        this.eventCode = eventCode;
        this.channel = channel;
        this.recipientType = recipientType;
        this.requiredVariables = requiredVariables;
        this.titleTemplate = "";
        this.bodyTemplate = "";
        this.contentConfirmed = false;
        this.active = true;
        this.reviewStatus = channel == NotificationChannel.KAKAO_ALIMTALK
                ? ReviewStatus.DRAFT
                : ReviewStatus.NOT_REQUIRED;
    }

    /**
     * 문구 수정.
     *
     * <p><b>★ 승인된 알림톡 문구를 고치면 심사 상태가 {@code DRAFT}로 돌아간다.</b>
     * 카카오는 승인받은 문구 그대로만 발송을 허용하므로, 고친 문구를 그대로 보내면
     * <b>발송이 거절된다.</b> 상태를 유지하면 "승인됐다"고 표시되면서 실제로는 안 나가는
     * 상태가 되어, 알림이 안 가는 이유를 찾기 어려워진다.
     */
    public void updateContent(String titleTemplate, String bodyTemplate,
                              String requiredVariables, boolean contentConfirmed) {
        boolean changed = !java.util.Objects.equals(this.titleTemplate, titleTemplate)
                || !java.util.Objects.equals(this.bodyTemplate, bodyTemplate);

        this.titleTemplate = titleTemplate;
        this.bodyTemplate = bodyTemplate;
        if (requiredVariables != null) {
            this.requiredVariables = requiredVariables;
        }
        this.contentConfirmed = contentConfirmed;

        if (changed && this.reviewStatus == ReviewStatus.APPROVED) {
            this.reviewStatus = ReviewStatus.DRAFT;
            this.reviewNote = "문구가 변경되어 재심사가 필요합니다.";
            this.reviewedAt = null;
        }
    }

    public void changeChannel(NotificationChannel channel, RecipientType recipientType) {
        if (channel != null && channel != this.channel) {
            this.channel = channel;
            // 채널이 바뀌면 심사 축도 바뀐다 — FCM은 심사가 없고, 알림톡은 새로 받아야 한다
            this.reviewStatus = channel == NotificationChannel.KAKAO_ALIMTALK
                    ? ReviewStatus.DRAFT
                    : ReviewStatus.NOT_REQUIRED;
            this.reviewedAt = null;
        }
        if (recipientType != null) {
            this.recipientType = recipientType;
        }
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /** 카카오에 제출. 제출 시점의 템플릿 코드를 함께 기록한다. */
    public void submitForReview(String kakaoTemplateCode, java.time.Instant at) {
        this.reviewStatus = ReviewStatus.SUBMITTED;
        this.kakaoTemplateCode = kakaoTemplateCode;
        this.reviewNote = null;
        this.reviewedAt = at;
    }

    public void approveReview(java.time.Instant at) {
        this.reviewStatus = ReviewStatus.APPROVED;
        this.reviewNote = null;
        this.reviewedAt = at;
    }

    public void rejectReview(String note, java.time.Instant at) {
        this.reviewStatus = ReviewStatus.REJECTED;
        this.reviewNote = note;
        this.reviewedAt = at;
    }

    /**
     * 지금 실제로 발송 가능한가.
     *
     * <p>세 조건이 <b>모두</b> 필요하다 — 활성 · 문구 확정(I-4) · 심사 통과(E-5).
     * 어느 하나라도 빠지면 이력만 남기고 건너뛴다.
     */
    public boolean isSendable() {
        if (!active || !contentConfirmed) {
            return false;
        }
        // ★ 알림톡은 NOT_REQUIRED 로 통과시키지 않는다.
        //   카카오는 사전 승인 문안만 받으므로, 심사를 안 거친 알림톡은 실제로 거절된다 —
        //   화면은 "나감"인데 안 나가고 원인이 우리 쪽에 안 남는다.
        //   실제로 마이그레이션 순서 때문에 알림톡 2건이 NOT_REQUIRED 로 들어가 있었다.
        if (channel == NotificationChannel.KAKAO_ALIMTALK) {
            return reviewStatus == ReviewStatus.APPROVED;
        }
        return reviewStatus.canSend();
    }

    public Set<String> requiredVariableSet() {
        Set<String> names = new LinkedHashSet<>();
        names.add(REQUIRED_STUDENT_NAME);
        Arrays.stream(requiredVariables.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(names::add);
        return names;
    }
}
