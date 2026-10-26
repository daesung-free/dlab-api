package com.dlab.domain.notification.service;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Student;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 알림 발송 요청 한 건.
 *
 * @param dedupKey 중복 발송 방지 키. null이면 중복 검사를 하지 않는다.
 */
public record NotificationCommand(
        NotificationEvent event,
        Account recipient,
        Student student,
        Academy academy,
        Short year,
        Map<String, String> variables,
        String dedupKey,
        String recipientPhone,
        String recipientName
) {

    public NotificationCommand {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        if (recipient == null && (recipientPhone == null || recipientPhone.isBlank())) {
            // ⚠️ 둘 다 비면 「누구에게 보내려 했는지」를 잃는다. DB CHECK 도 같은 것을 막지만,
            //    거기까지 가면 트랜잭션이 통째로 터져 본 행위까지 되돌아간다
            throw new IllegalArgumentException("수신자 계정이나 번호 중 하나는 필요합니다.");
        }
    }

    /** 계정 수신자용 — 기존 호출부가 쓰는 모양 그대로다. */
    public NotificationCommand(NotificationEvent event, Account recipient, Student student,
                               Academy academy, Short year, Map<String, String> variables,
                               String dedupKey) {
        this(event, recipient, student, academy, year, variables, dedupKey, null, null);
    }

    /**
     * 계정 없는 수신자에게 — <b>번호로 보낸다</b>.
     *
     * <p>★ 입학 대기자({@code admission_reservation} — 학생이 아니라 계정이 없다)와
     * 급식업체(사업자라 계정이 없다)가 이 경로를 쓴다.
     *
     * <p>⚠️ <b>{@code studentName} 변수를 자동으로 채우지 않는다.</b> 대기자는 학생 행이
     * 없어서 넣을 값이 없고, 템플릿이 그 변수를 요구하면 <b>「변수 누락」으로 걸러진다</b> —
     * 그게 맞는 동작이다(빈 이름으로 나가는 것보다 낫다). 대기자 템플릿은 이름을
     * {@code variables} 로 직접 받는다.
     */
    public static NotificationCommand toPhone(NotificationEvent event, String phone,
                                              String name, Academy academy, Short year,
                                              Map<String, String> variables, String dedupKey) {
        return new NotificationCommand(event, null, null, academy, year, variables, dedupKey,
                phone, name);
    }

    /**
     * 계정 없는 보호자에게 — <b>번호로 보내되 학생 정보는 남긴다</b>.
     *
     * <h2>★★ {@code toPhone} 과 가른 이유</h2>
     * {@code toPhone} 은 학생을 받지 않아 <b>학생명 변수가 빠진다.</b> 모든 학생 관련
     * 알림에 학생명이 필수인데(다자녀 학부모가 「이거 누구 얘기지」 헷갈리지 않게),
     * 그걸로 보내면 <b>미등원 알림에 이름이 안 들어간다.</b>
     * {@code toPhone} 은 급식업체·입학 대기자처럼 <b>학생과 무관한</b> 수신자용이다.
     *
     * <p>★ 앱을 설치하지 않은 보호자가 이 경로를 탄다 — 신입생 동의서가 보호자 번호를
     * 필수로 수집하므로 <b>번호만 있어도 보내는 것이 맞다.</b>
     */
    public static NotificationCommand forStudentPhone(NotificationEvent event, String phone,
                                                      String receiverName, Student student,
                                                      Academy academy, Short year,
                                                      Map<String, String> variables,
                                                      String dedupKey) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("studentName", student.getName());
        if (variables != null) {
            merged.putAll(variables);
        }
        return new NotificationCommand(event, null, student, academy, year, merged, dedupKey,
                phone, receiverName);
    }

    /**
     * 학생명 변수를 자동으로 채워 넣은 커맨드를 만든다.
     * 모든 학생 관련 알림에 학생명이 필수이므로 호출부가 빠뜨리지 않게 여기서 넣는다.
     */
    public static NotificationCommand forStudent(NotificationEvent event, Account recipient,
                                                 Student student, Academy academy, Short year,
                                                 Map<String, String> variables, String dedupKey) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("studentName", student.getName());
        if (variables != null) {
            merged.putAll(variables);
        }
        return new NotificationCommand(event, recipient, student, academy, year, merged, dedupKey);
    }
}
