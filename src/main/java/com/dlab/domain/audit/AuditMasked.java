package com.dlab.domain.audit;

import java.lang.annotation.*;

/**
 * 이 필드는 감사 로그에 <b>값을 남기지 않는다</b>. 바뀌었다는 사실만 남고 값은 {@code ***}다.
 *
 * <p>비밀번호 해시·키오스크 시크릿·PG 가맹점 정보처럼 <b>남기면 안 되는 값</b>에 붙인다.
 * 지점 설정 이력에서 한 판단과 같다 — 비밀값을 이력에 복사하면 <b>두 곳으로 늘어나고,
 * 폐기한 값이 영구히 보존된다</b>.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditMasked {
}
