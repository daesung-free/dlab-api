package com.dlab.domain.notification.entity;

/**
 * 미리보기에 확정된 수신자 한 명 (jsonb 스냅샷의 한 줄).
 *
 * <p>★ <b>학생 한 명이 여러 줄이 될 수 있다.</b> 템플릿 수신자가 {@code BOTH} 거나
 * 부·모가 각각 앱 계정을 만든 경우다 — 학부모 승인이 「양쪽 알림 + 선착순」인 것과 같은
 * 구조라, 연결된 학부모 <b>전원</b>이 받는다.
 *
 * <p>★ {@code accountId} 를 박아 둔다. 발송 시점에 다시 풀면 <b>미리보기 뒤에 추가된
 * 학부모에게도 나가</b> 담당자가 본 명단과 어긋난다.
 *
 * @param enrollmentId 등록 건. 화면이 「누구에게 갔나」를 학번·반과 함께 보여주는 키
 * @param studentId    사람(student). 알림 이력이 사람에 붙는다 — 기수가 아니다
 * @param accountId    실제 수신 계정
 * @param receiverName 수신자 이름. 발송 당시 이름을 남긴다
 */
public record ManualSendTarget(
        Long enrollmentId,
        Long studentId,
        /** ★ 앱을 설치하지 않은 보호자는 {@code null} 이다 — 그때는 번호로 보낸다. */
        Long accountId,
        String receiverName,
        /** 계정이 없을 때 쓰는 수신 번호. */
        String receiverPhone
) {
}
