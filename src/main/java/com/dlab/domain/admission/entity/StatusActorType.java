package com.dlab.domain.admission.entity;

/**
 * 상담 상태를 바꾼 주체.
 *
 * <p>★ <b>{@code created_by} 로는 구분할 수 없다.</b> 공개 링크는 인증 주체가 없어
 * {@code SecurityAuditorAware} 가 시스템 계정({@code 0})으로 기록하는데, 배치·DSA 호환
 * 구획도 같은 {@code 0} 이라 「학생이 눌렀다」와 「배치가 했다」가 한 값이 된다.
 *
 * <p>전화 받아 적는 일을 없애는 것이 목적이라 <b>학생 응답이 상태를 바로 바꾸되</b>,
 * 직원이 관리자 웹에서 덮을 수 있고 그 변경은 「직원 변경」으로 남아야 한다.
 */
public enum StatusActorType {
    /** 공개 링크에서 학생·학부모가 버튼을 눌렀다 */
    STUDENT,
    /** 관리자 웹에서 직원이 바꿨다 */
    STAFF;

    public String displayName() {
        return this == STUDENT ? "학생 응답" : "직원 변경";
    }
}
