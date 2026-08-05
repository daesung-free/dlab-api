package com.dlab.api.kiosk.attendance;

import com.dlab.api.kiosk.DsaCode;
import com.dlab.api.kiosk.dto.AttendTagRequest;
import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.RfidRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskAttendanceService;
import com.dlab.domain.kiosk.service.KioskAttendanceService.TagResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 출결 (DSA 3.14 · 3.22). <b>이 프로젝트 출결의 진입점이다.</b>
 *
 * <h2>응답 코드가 두 자리에 나뉘어 나간다</h2>
 * 원본 DSA가 그랬고 키오스크가 <b>양쪽을 다 뒤지도록</b> 구현돼 있다:
 * <ul>
 *   <li><b>최상위</b> {@code code} — {@code 121}(이미 조퇴) · {@code 130}(승인 없음)</li>
 *   <li><b>{@code data} 내부</b> {@code code} — {@code 113} · {@code 122} ·
 *       {@code 126}/{@code 128}/{@code 129}. 이때 <b>최상위는 0</b>이다</li>
 * </ul>
 * 위치가 바뀌면 키오스크가 분기를 놓치고 로컬 판별로 폴백해버린다 — 조용히 틀린 출결이 남는다.
 *
 * <h2>{@code 113}은 stateless 2-phase다</h2>
 * 1차 응답으로 {@code 113}을 받으면 키오스크가 학생에게 "하원/외출"을 묻고
 * {@code con_gn}을 실어 <b>재호출</b>한다. <b>서버는 그 사이 아무 상태도 들고 있지 않는다</b> —
 * 세션·임시저장을 만들지 말 것.
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskAttendanceController {

    private final KioskAttendanceService attendanceService;
    private final DsaTokenService tokenService;

    /** 3.14 — 출결 태깅. */
    @PostMapping("/setAttendStd")
    public DsaResponse tag(@RequestBody AttendTagRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        TagResult result = attendanceService.tag(
                academyId, request.rfidNo(), request.tagDt(), request.conGn());

        if (result.isAccepted()) {
            // 성공은 att_gn까지 전부 최상위다(규격서 샘플). data는 비운다
            return DsaResponse.ok()
                    .with("hak_no", result.hakNo())
                    .with("std_nm", result.stdNm())
                    .with("att_gn", result.attGn());
        }
        return branch(result);
    }

    /**
     * 분기 응답. 코드가 최상위인지 {@code data} 내부인지는 {@link DsaCode#atTopLevel()}이 안다.
     *
     * <p>학번·학생명은 <b>어느 경우에도</b> 싣는다 — 거부 화면에도 이름이 떠야
     * 학생이 자기 건인지 알 수 있다.
     */
    private DsaResponse branch(TagResult result) {
        DsaCode code = result.code();

        if (code.atTopLevel()) {
            return DsaResponse.error(code, result.message())
                    .with("hak_no", result.hakNo())
                    .with("std_nm", result.stdNm());
        }

        // 최상위는 0(성공)이고 거부 코드는 data 안에 있다.
        // 여기서 최상위에 코드를 실으면 키오스크가 121/130 분기로 잘못 들어간다
        return DsaResponse.ok(List.<Map<String, Object>>of(
                        Map.of("code", code.value(), "message", result.message())))
                .with("hak_no", result.hakNo())
                .with("std_nm", result.stdNm());
    }

    /**
     * 3.22 — 조퇴 해제 후 재등원.
     *
     * <p>응답은 {@code code}·{@code message}만이다(규격서). 키오스크도 성공 여부만 본다.
     */
    @PostMapping("/setReAttendProc")
    public DsaResponse reAttend(@RequestBody RfidRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        attendanceService.reAttend(academyId, request.rfidNo());
        return DsaResponse.ok();
    }
}
