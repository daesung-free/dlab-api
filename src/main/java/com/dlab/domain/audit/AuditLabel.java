package com.dlab.domain.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 감사 로그 화면에 보일 <b>필드 이름</b> (디랩 1021 피드백 4-2).
 *
 * <p>디랩 원문: <i>"변경항목과 변경 전/후 내용이 <b>실제 사용하는 메뉴명과 항목명칭</b>으로
 * 보여지면 좋을것 같습니다. 예를 들어 … (장학 50% > 장학 30%), (중식 20식 > 중식 18식)
 * 이런식으로 직관적이었으면 좋겠습니다."</i>
 *
 * <h2>★★ 왜 사전을 화면이 아니라 서버에 두는가</h2>
 * 화면에 사전을 두면 <b>서버가 필드를 추가할 때마다 그 항목이 영어로 보이고, 아무도
 * 알려주지 않는다.</b> 지금 화면 사전에 계정 관련 7개만 들어 있어 나머지가 전부 서버
 * 필드명(`graduationYear`·`deleted`)으로 보이는 것이 그 결과다.
 *
 * <p>감사 대상이 늘면(4-1) 필드가 수십 개 늘어난다 — 그때 화면을 다시 훑지 않아도 되게
 * <b>엔티티 옆에 이름을 적어 둔다.</b> {@link Audited} 가 엔티티 이름을 들고 있는 것과
 * 같은 자리다.
 *
 * <h2>★ 라벨이 없으면 필드명을 그대로 내린다</h2>
 * 숨기지 않는다 — 기존 화면 주석이 적어 둔 판단이 옳다:
 * <i>"모르는 코드는 그대로 보인다 — 숨기면 무슨 일이 있었는지 아예 사라진다."</i>
 * 다만 <b>라벨이 빠진 필드를 테스트가 알려준다</b>({@code AuditLabelCoverageTest}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditLabel {

    /** 화면에 보일 한국어 이름. 예: {@code "졸업연도"} */
    String value();
}
