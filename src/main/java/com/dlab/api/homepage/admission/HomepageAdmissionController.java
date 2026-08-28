package com.dlab.api.homepage.admission;

import com.dlab.api.homepage.dto.HomepageRequests;
import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.CommonCode;
import com.dlab.domain.admission.service.AdmissionReservationService;
import com.dlab.domain.admission.service.HomepageTokenService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈페이지 입학예약 (규격서 3.3~3.8).
 *
 * <p>DLab 홈페이지 입학예약창이 지금까지 대성전산 {@code api.dshw.co.kr}을 호출해 왔다.
 * <b>그 자리에 우리가 앉는다</b> — 홈페이지는 주소만 바꾸고 코드는 그대로다.
 *
 * <h2>키오스크 구획과 같은 예외</h2>
 * <ul>
 *   <li>경로에 {@code /api/v1}을 붙이지 않는다</li>
 *   <li>응답이 {@code ApiResponse}가 아니라 {@link DsaResponse}다 — 성공 판정이
 *       {@code code == 0}이고 HTTP는 항상 200이다</li>
 * </ul>
 *
 * <p><b>인증은 본문 {@code token}으로 직접 한다.</b> 키오스크 토큰과는 저장소가 갈라져
 * 있어 서로 통하지 않는다 — 지점 하나의 자격증명이 새도 지원자 정보는 열리지 않는다.
 */
@RestController
@RequestMapping("/dlab")
@RequiredArgsConstructor
public class HomepageAdmissionController {

    private final HomepageTokenService tokenService;
    private final AdmissionReservationService admissionService;

    /** 3.3 — 입학예약 저장. {@code rsv_cd}는 최상위에 실린다(규격서 샘플). */
    @PostMapping("/setStdInfo")
    public DsaResponse setStdInfo(@RequestBody HomepageRequests.SaveStdInfo request) {
        tokenService.verify(request.token());
        String rsvCd = admissionService.save(request.toCommand());
        return DsaResponse.ok().with("rsv_cd", rsvCd);
    }

    /** 3.4 — 조회. 같은 사람이 여러 번 신청할 수 있어 배열이다. */
    @PostMapping("/getStdInfo")
    public DsaResponse getStdInfo(@RequestBody HomepageRequests.GetStdInfo request) {
        tokenService.verify(request.token());
        List<Map<String, Object>> rows = admissionService.search(
                        request.acid(), request.preTest(), request.rsvNm(),
                        request.birth(), request.stdTel())
                .stream().map(this::toRow).toList();
        return DsaResponse.ok(rows);
    }

    /** 3.5 — 공통코드. ⚠️ 값 목록 미수령이라 지금은 빈 배열이 나간다. */
    @PostMapping("/getComInfo")
    public DsaResponse getComInfo(@RequestBody HomepageRequests.GetComInfo request) {
        tokenService.verify(request.token());
        return DsaResponse.ok(admissionService.commonCodes(request.acid(), request.grp3())
                .stream().map(this::toCodeRow).toList());
    }

    /** 3.6 — 과목. {@code idx}는 국어1·수학2·영어3·탐구1=4·탐구2=5. */
    @PostMapping("/getSubInfo")
    public DsaResponse getSubInfo(@RequestBody HomepageRequests.GetSubInfo request) {
        tokenService.verify(request.token());
        return DsaResponse.ok(admissionService.subjects().stream()
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("gm_cd", c.getCode());
                    row.put("gm_nm", c.getName());
                    row.put("idx", c.getIdx() == null ? null : String.valueOf(c.getIdx()));
                    return row;
                })
                .toList());
    }

    /** 3.7 — 지원기준 성적. 같은 과목·구분을 다시 보내면 덮어쓴다. */
    @PostMapping("/setStdTest")
    public DsaResponse setStdTest(@RequestBody HomepageRequests.SaveStdTest request) {
        tokenService.verify(request.token());
        admissionService.saveScore(request.rsvCd(), request.scoreType(),
                request.subject(), request.score());
        return DsaResponse.ok();
    }

    /** 3.8 — 성적표 파일(Base64 Data URL). */
    @PostMapping("/setStdFile")
    public DsaResponse setStdFile(@RequestBody HomepageRequests.SaveStdFile request) {
        tokenService.verify(request.token());
        admissionService.saveFile(request.rsvCd(), request.file());
        return DsaResponse.ok();
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 조회 응답 한 줄.
     *
     * <p>규격서 3.4의 필드명·순서 그대로다. <b>코드값이 아니라 이름({@code _nm})을 주게
     * 돼 있는데 공통코드 목록을 아직 못 받아 채울 수 없다</b> — 지금은 코드를 문자열로
     * 넣고, 목록이 오면 여기서 이름으로 바꾼다.
     */
    private Map<String, Object> toRow(AdmissionReservation r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rsv_cd", r.getRsvCd());
        row.put("rsv_nm", r.getStudentName());
        row.put("std_tel", r.getStudentTel());
        row.put("gender_gb", r.getGender());
        row.put("birth", r.getBirth());
        row.put("adm_dt", r.getAdmDt());
        row.put("gender_gb_nm", "M".equals(r.getGender()) ? "남" : "F".equals(r.getGender()) ? "여" : null);
        row.put("geyul_gb_nm", geyulName(r.getGeyulGb()));
        row.put("pre_test_nm", asText(r.getPreTest()));
        row.put("find_gb_nm", asText(r.getFindGb()));
        row.put("find_txt", r.getFindTxt());
        row.put("sch_cd_nm", asText(r.getSchCd()));
        row.put("zip", r.getZip());
        row.put("addr1", r.getAddr1());
        row.put("addr2", r.getAddr2());
        row.put("sch_nm_high", r.getSchNmHigh());
        row.put("grade", r.getStdGrade());
        return row;
    }

    private Map<String, Object> toCodeRow(CommonCode c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cd", c.getCode());
        row.put("nm", c.getName());
        return row;
    }

    /** 인문1·자연2·예체능3·공통9. 규격서가 이름으로 주게 돼 있다. */
    private String geyulName(Short geyulGb) {
        if (geyulGb == null) {
            return null;
        }
        return switch (geyulGb) {
            case 1 -> "인문";
            case 2 -> "자연";
            case 3 -> "예체능";
            case 9 -> "공통";
            default -> String.valueOf(geyulGb);
        };
    }

    private String asText(Integer code) {
        return code == null ? null : String.valueOf(code);
    }
}
