package com.dlab.domain.audit;

import java.lang.annotation.*;

/**
 * 이 엔티티의 변경을 감사 로그(F-C-1)에 남긴다.
 *
 * <p><b>전 테이블을 켜지 않는 이유.</b> 출결 태깅 로그처럼 원래 append-only 인 것까지
 * 켜면 <b>로그가 원장보다 커진다</b>. 켜는 쪽이 명시적으로 고르게 둔다.
 *
 * <p>무엇을 켜야 하나 — <b>사람이 고칠 수 있고, 고친 사실이 다툼이 될 수 있는 것</b>이다.
 * 상벌점·출결 정정·성적·학생 정보·청구가 그렇다. 반대로 시스템이 쌓기만 하는 로그,
 * 마스터 조회용 코드표는 켤 이유가 없다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Audited {

    /** 화면에 보일 이름. 비우면 클래스 이름을 쓴다. */
    String value() default "";
}
