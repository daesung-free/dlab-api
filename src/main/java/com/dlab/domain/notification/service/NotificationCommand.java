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
        String dedupKey
) {

    public NotificationCommand {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
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
