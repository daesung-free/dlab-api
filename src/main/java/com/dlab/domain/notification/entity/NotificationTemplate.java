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

    @Column(name = "content_confirmed", nullable = false)
    private boolean contentConfirmed;

    @Column(nullable = false)
    private boolean active;

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
