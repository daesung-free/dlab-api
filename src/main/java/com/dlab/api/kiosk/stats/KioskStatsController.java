package com.dlab.api.kiosk.stats;

import com.dlab.api.kiosk.dto.DateRangeRequest;
import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.RfidDateRangeRequest;
import com.dlab.api.kiosk.dto.RfidMonthRequest;
import com.dlab.api.kiosk.dto.TokenOnlyRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskStatsQueryService;
import com.dlab.domain.kiosk.service.KioskStatsQueryService.AttendCounts;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 통계·순공시간.
 *
 * <p><b>이 구획은 부가 필드가 최상위에 흩뿌려진다</b>({@code total_inwon}·{@code study_tm}·
 * {@code absence_cnt} …). 키오스크가 {@code data}가 아니라 {@code extra}에서 꺼내므로
 * {@code data} 안으로 옮기면 전부 0으로 읽힌다(그쪽 {@code DashboardService}).
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskStatsController {

    private final KioskStatsQueryService statsService;
    private final DsaTokenService tokenService;

    /** 3.3 — 현재 재실 인원. */
    @PostMapping("/getTotalAttendCount")
    public DsaResponse totalAttendCount(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok()
                .with("total_inwon", String.valueOf(statsService.currentlyPresent(academyId)));
    }

    /** 3.21 — 기간 내 조퇴·결석·지각 수(지점 전체). */
    @PostMapping("/getAttendState")
    public DsaResponse attendState(@RequestBody DateRangeRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        AttendCounts c = statsService.attendCounts(
                academyId, request.startDate(), request.endDate());

        return DsaResponse.ok()
                .with("early_cnt", String.valueOf(c.earlyLeave()))
                .with("absence_cnt", String.valueOf(c.absence()))
                .with("late_cnt", String.valueOf(c.lateOrOuting()));
    }

    /** 3.26 — 학생별 결석·조퇴·외출 횟수. */
    @PostMapping("/getStdAttendState")
    public DsaResponse studentAttendState(@RequestBody RfidDateRangeRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        AttendCounts c = statsService.studentAttendState(
                academyId, request.rfidNo(), request.startDate(), request.endDate());

        return DsaResponse.ok()
                .with("absence_cnt", String.valueOf(c.absence()))
                .with("early_cnt", String.valueOf(c.earlyLeave()))
                .with("out_cnt", String.valueOf(c.lateOrOuting()));
    }

    /** 3.15 — 학생별 당월 지각 수. 키오스크는 최상위 {@code beLate}만 읽는다. */
    @PostMapping("/getAttendListStd")
    public DsaResponse attendListStd(@RequestBody RfidMonthRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok().with("beLate", String.valueOf(
                statsService.lateCount(academyId, request.rfidNo(), request.month())));
    }

    /** 3.27 — 기간별·일별 순공시간. */
    @PostMapping("/getStudyTimeList")
    public DsaResponse studyTimeList(@RequestBody DateRangeRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(
                statsService.studyTimes(academyId, request.startDate(), request.endDate()).stream()
                        .map(r -> row("study_dt", r.studyDt(), "std_nm", r.stdNm(),
                                "std_no", r.stdNo(), "study_tm", r.studyTm()))
                        .toList());
    }

    /**
     * 3.11 — 전주 순공 순위.
     *
     * <p>★ <b>시간 필드를 {@code study_tm}·{@code att_tm} 둘 다 싣는다.</b>
     * 규격서는 {@code study_tm}인데 키오스크는 {@code att_tm}을 읽고
     * <b>없으면 기본값 {@code "0시간 0분"}으로 대체</b>한다(그쪽 {@code StudyRankingService}).
     * 하나만 내리면 순위표가 전원 0분으로 뜨는데 에러가 안 나서 원인을 찾기 어렵다.
     */
    @PostMapping("/getLastWeekStudyTimeList")
    public DsaResponse lastWeekStudyTimeList(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(statsService.lastWeekRanking(academyId).stream()
                .map(r -> row("rank", r.rank(), "std_nm", r.stdNm(),
                        "study_tm", r.studyTm(), "att_tm", r.studyTm()))
                .toList());
    }

    /** 3.5 — 전주 1위(동점자 가능). 없으면 빈 목록. */
    @PostMapping("/getFirstLastWeekStudyTimeStd")
    public DsaResponse firstLastWeekStudyTimeStd(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(statsService.lastWeekTop(academyId).stream()
                .map(r -> row("std_nm", r.stdNm(), "study_tm", r.studyTm(), "att_tm", r.studyTm()))
                .toList());
    }

    /** 3.6 — 전주 평균. 최상위 {@code study_tm}. */
    @PostMapping("/getAvgLastWeekStudyTime")
    public DsaResponse avgLastWeekStudyTime(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok().with("study_tm", statsService.lastWeekAverage(academyId));
    }

    /** 순서를 보존해야 응답이 규격서 표기 순으로 나간다. */
    private Map<String, Object> row(String... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
