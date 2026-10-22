package com.dlab.domain.university.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학과 마스터 (디랩 1021 피드백 6-1).
 *
 * <h2>★★ 키는 {@code (대학코드, 학과코드)} 다</h2>
 * 학과코드만으로는 유일하지 않다 — 실측 2,487개 중 <b>503개가 둘 이상의 대학</b>에서
 * 쓰인다.
 *
 * <h2>★★ 학과명을 학과코드 사전으로 쓰지 말 것</h2>
 * 같은 학과코드에 다른 이름이 있다. 실측 3건이고 전부 표기 차이다.
 * <pre>
 * 6274  명지대·단국대 「문예창작」      vs  추계예술대 「문예창작과」
 * 7627  중앙대 「…-비실기」            vs  영산대 「…(비실기)」
 * 7628  중앙대 「…-실기」              vs  영산대 「…(실기)」
 * </pre>
 * 그래서 이름이 <b>이 행에</b> 있다. 전달 문서는 *"충돌 0건"* 이라고 했으나 파일을
 * 직접 파싱하면 4건이 나온다 — 그 설명을 근거로 코드→이름 사전을 만들지 말 것.
 *
 * <h2>★ 계열은 학과코드마다 고정이다 (충돌 0건)</h2>
 * 대학·학과를 고르면 계열이 따라온다 — <b>계열을 따로 입력받지 말 것.</b>
 * ⚠️ 계열코드가 {@code 1}·{@code 2}·{@code 3}·{@code 7} 이다(4~6 없음).
 * 연속이라고 가정하지 말 것.
 */
@Entity
@Table(name = "university_department")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UniversityDepartment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Short year;

    @Column(name = "university_code", nullable = false, length = 10)
    private String universityCode;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "track_code", length = 10)
    private String trackCode;

    @Column(name = "track_name", length = 50)
    private String trackName;

    public UniversityDepartment(Short year, String universityCode, String code, String name,
                                String trackCode, String trackName) {
        this.year = year;
        this.universityCode = universityCode;
        this.code = code;
        this.name = name;
        this.trackCode = trackCode;
        this.trackName = trackName;
    }

    /** 재업로드 때 값이 바뀐 경우에만 쓴다 — 행을 늘리지 않는다. */
    public void update(String name, String trackCode, String trackName) {
        this.name = name;
        this.trackCode = trackCode;
        this.trackName = trackName;
    }
}
