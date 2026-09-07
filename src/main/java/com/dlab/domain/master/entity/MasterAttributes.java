package com.dlab.domain.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기초 마스터 공통 속성 — 코드 · 비고 · 사용여부.
 *
 * <p>학과·과정·교습비·커리큘럼 넷이 같은 세 칸을 갖는다. 네 곳에 따로 두면
 * <b>한 곳만 고쳐지고 나머지가 뒤처진다</b> — 실제로 지금까지 {@code sortOrder}가
 * 과정·교습비·커리큘럼에만 있고 학과에는 없었다.
 *
 * <h2>{@code code}가 왜 필요한가</h2>
 * 이름은 바뀐다("이과" → "자연계열"). 엑셀 업로드·외부 연동·과거 데이터 대조가
 * 이름으로만 이어져 있으면 <b>이름을 고치는 순간 전부 끊긴다</b>.
 * 코드는 안 바뀌는 키다. 선택 입력이라 안 쓰는 마스터는 비워 두면 된다.
 *
 * <h2>{@code active}는 삭제와 다르다</h2>
 * 지난 기수 과정을 <b>지우면 그 과정에 배정된 반의 이력이 무엇이었는지</b> 알 수 없게
 * 된다(그래서 삭제도 soft delete다). {@code active = false}는 "새로 고를 수 없다"는
 * 뜻이고, 이미 그 값을 쓰는 데이터는 그대로 남는다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MasterAttributes {

    /** 선택. 지점·연도 안에서 유일하다 — 있으면 이름보다 이게 키다. */
    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "memo", length = 200)
    private String memo;

    /** 기본 {@code true}. {@code false}면 새로 고를 수 없고 기존 데이터는 남는다. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    public MasterAttributes(String code, String memo) {
        this.code = normalize(code);
        this.memo = memo;
        this.active = true;
    }

    /** 기본값 — 코드·비고 없이 사용중. 기존 생성 경로가 그대로 쓴다. */
    public static MasterAttributes empty() {
        return new MasterAttributes(null, null);
    }

    /**
     * 코드·비고 수정.
     *
     * <p><b>{@code null}은 "안 바꿈"이 아니라 "지움"이다.</b> 부분 수정으로 만들면
     * 코드를 비우는 방법이 없어진다 — 화면은 항상 현재 값을 실어 보낸다.
     */
    public void update(String code, String memo) {
        this.code = normalize(code);
        this.memo = memo;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    /**
     * 대조에 쓰이는 값이라 공백·대소문자를 정리한다. 빈 문자열은 {@code null}로 본다.
     *
     * <p>중복 검사도 <b>같은 정리를 거친 값으로</b> 해야 한다 — 안 그러면 {@code "a"}와
     * {@code "A"}가 검사를 통과한 뒤 DB 유니크 제약에서 터진다.
     */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.strip().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
