package com.dlab.domain.facility.service;

import com.dlab.domain.facility.entity.Building;
import org.springframework.stereotype.Component;

/**
 * 우리 코드 → 키오스크에 내릴 코드.
 *
 * <p><b>한 군데 모아 둔 이유</b> — 구역과 좌석 두 곳에서 같은 규칙이 필요한데, 각자
 * 만들면 한쪽만 고쳐져서 <b>구역은 찾았는데 그 안의 좌석은 못 찾는</b> 상태가 된다.
 * 그 증상은 단말에서만 보이고 우리 화면·DB 는 멀쩡하다.
 *
 * <h2>변환 결과는 저장한다</h2>
 * 여기서 만든 값은 {@code study_area.kiosk_area_cd}·{@code seat_master.kiosk_seat_cd}에
 * 들어가고, <b>UNIQUE 제약이 충돌을 등록 시점에 막는다.</b> 응답할 때마다 계산하면
 * 본관에 이미 1001번이 있는 경우(= 별관 1번의 변환 결과와 같다) 조용히 겹친다.
 */
@Component
public class KioskCodeTranslator {

    /**
     * 좌석번호.
     *
     * <p>본관은 그대로다. 별관은 <b>숫자면 offset 을 더하고</b>(DSA 가 1번→1001번으로
     * 쓰던 방식 그대로라 기존 운영과 값이 같다), 숫자가 아니면 관 코드를 앞에 붙인다.
     * 격자 등록이 {@code "A-01"} 같은 접두어를 허용하므로 숫자가 아닌 경우가 실제로 있다.
     */
    public String seatCd(Building building, String seatCd) {
        if (building.isMain()) {
            return seatCd;
        }
        Integer number = asNumber(seatCd);
        return number == null
                ? building.getCode() + "-" + seatCd
                : String.valueOf(building.getSeatCdOffset() + number);
    }

    /**
     * 구역코드.
     *
     * <p>구역은 {@code "A"}·{@code "B"}처럼 숫자가 아닌 게 보통이라 offset 을 쓸 수 없다.
     * 관 코드를 앞에 붙인다 — 별관 A 구역이 {@code "2관-A"}가 된다.
     */
    public String areaCd(Building building, String areaCd) {
        return building.isMain() ? areaCd : building.getCode() + "-" + areaCd;
    }

    private Integer asNumber(String value) {
        if (value == null || value.isBlank() || value.length() > 9) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        return Integer.valueOf(value);
    }
}
