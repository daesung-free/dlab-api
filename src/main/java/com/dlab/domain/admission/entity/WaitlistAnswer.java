package com.dlab.domain.admission.entity;

/**
 * 대기자가 공개 링크에서 누른 답.
 *
 * <h2>★ 상담 상태로 바로 옮긴다</h2>
 * 별도 컬럼에 답만 쌓아두면 직원이 그걸 보고 상태를 또 바꿔야 한다 — <b>전화 받아
 * 적는 일을 없애는 것이 목적</b>이라 상태를 즉시 반영한다.
 *
 * <h2>⚠️ 「입학확정」이 학생을 만드는 것은 아니다</h2>
 * {@code CONFIRMED} 는 <b>전환이 가능해지는 상태</b>일 뿐이고, 학번 채번은 직원이
 * 별도로 수행하는 정식 접수 전환이 한다. 공개 링크 클릭 하나로 학번이 나가지는 않는다 —
 * 토큰이 새더라도 사람의 손을 한 번 더 거친다.
 */
public enum WaitlistAnswer {
    /** 등록하겠다 → 입학확정 */
    ACCEPT(ConsultStatus.CONFIRMED),
    /** 등록하지 않겠다 → 미등록 */
    DECLINE(ConsultStatus.NOT_REGISTERED);

    private final ConsultStatus status;

    WaitlistAnswer(ConsultStatus status) {
        this.status = status;
    }

    public ConsultStatus status() {
        return status;
    }

    /**
     * 이력에 남은 상태에서 되읽는다.
     *
     * <p>★ <b>답을 따로 저장하지 않는 이유다.</b> 학생 응답 이력의 {@code toStatus} 는
     * 바뀌지 않으므로, 직원이 나중에 상태를 덮어도 <b>「학생이 무엇을 눌렀는가」는
     * 그대로 남는다.</b> 답을 별도 컬럼에 또 두면 둘이 어긋날 수 있다.
     */
    public static WaitlistAnswer fromStatus(ConsultStatus status) {
        for (WaitlistAnswer answer : values()) {
            if (answer.status == status) {
                return answer;
            }
        }
        // 응답으로 만들어질 수 없는 상태다(직원 변경 이력을 잘못 넘긴 경우)
        return null;
    }
}
