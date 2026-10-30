package com.dlab.api.homepage.dto;

import com.dlab.domain.admission.service.AdmissionReservationService.SaveCommand;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 홈페이지 입학예약 요청 (규격서 3.1~3.8).
 *
 * <p>필드명이 스네이크케이스인 것은 <b>규격서 그대로</b>다. 홈페이지가 이 이름으로 보낸다 —
 * 예쁘게 고치면 상대가 못 읽는다(키오스크 구획과 같은 원칙).
 */
public final class HomepageRequests {

    private HomepageRequests() {
    }

    public record Token(@JsonProperty("client_id") String clientId,
                        @JsonProperty("secret_id") String secretId,
                        @JsonProperty("service") String service,
                        @JsonProperty("user_id") String userId) {
    }

    public record Refresh(@JsonProperty("client_id") String clientId,
                          @JsonProperty("refreshToken") String refreshToken) {
    }

    /** 3.3 원생 정보 저장. */
    public record SaveStdInfo(@JsonProperty("token") String token,
                              @JsonProperty("acid") String acid,
                              @JsonProperty("reg_yyyy") String regYyyy,
                              @JsonProperty("rsv_nm") String rsvNm,
                              @JsonProperty("std_tel") String stdTel,
                              @JsonProperty("par_tel") String parTel,
                              @JsonProperty("gender_gb") String genderGb,
                              @JsonProperty("birth") String birth,
                              @JsonProperty("geyul_gb") Short geyulGb,
                              @JsonProperty("adm_dt") String admDt,
                              @JsonProperty("pre_test") Integer preTest,
                              @JsonProperty("admi_st") Integer admiSt,
                              @JsonProperty("nasin_st") Short nasinSt,
                              @JsonProperty("nasin_sc") BigDecimal nasinSc,
                              @JsonProperty("uni_nm") String uniNm,
                              @JsonProperty("uni_gd") Short uniGd,
                              @JsonProperty("intr_st") Short intrSt,
                              @JsonProperty("intr_txt") String intrTxt,
                              @JsonProperty("find_gb") Integer findGb,
                              @JsonProperty("find_txt") String findTxt,
                              @JsonProperty("sch_cd") Integer schCd,
                              @JsonProperty("zip") String zip,
                              @JsonProperty("addr1") String addr1,
                              @JsonProperty("addr2") String addr2,
                              // ★★ 규격서 표기는 `_hight`(t 포함)이고 우리도 그대로 받는다.
                              //   그런데 보내는 쪽이 `_high` 로 보내는 경우가 실제로 있었다
                              //   (2026-10-26 연구소 테스트 — 3건 전부 null 로 저장됐다).
                              //   ⚠️ 바인딩이 조용히 실패해 **접수는 성공하고 값만 빈다** —
                              //   그래서 「저장이 안 됐다」가 아니라 「필드명이 달랐다」를
                              //   찾기까지 시간이 걸린다. 두 표기를 모두 받는다.
                              @JsonProperty("sch_cd_hight")
                              @JsonAlias("sch_cd_high") Integer schCdHigh,
                              @JsonProperty("sch_nm_hight")
                              @JsonAlias("sch_nm_high") String schNmHigh,
                              @JsonProperty("agree_ad") String agreeAd,
                              @JsonProperty("promo_ad") String promoAd,
                              @JsonProperty("std_grade") String stdGrade) {

        public SaveCommand toCommand() {
            return new SaveCommand(acid, regYyyy, rsvNm, stdTel, parTel, genderGb, birth,
                    geyulGb, admDt, preTest, admiSt, nasinSt, nasinSc, uniNm, uniGd,
                    intrSt, intrTxt, findGb, findTxt, schCd, zip, addr1, addr2,
                    schCdHigh, schNmHigh, agreeAd, promoAd, stdGrade);
        }
    }

    /** 3.4 원생 정보 조회. */
    public record GetStdInfo(@JsonProperty("token") String token,
                             @JsonProperty("acid") String acid,
                             @JsonProperty("pre_test") Integer preTest,
                             @JsonProperty("rsv_nm") String rsvNm,
                             @JsonProperty("birth") String birth,
                             @JsonProperty("std_tel") String stdTel) {
    }

    /** 3.5 공통코드 조회. */
    public record GetComInfo(@JsonProperty("token") String token,
                             @JsonProperty("acid") String acid,
                             @JsonProperty("grp3") String grp3) {
    }

    /** 3.6 과목 조회. {@code acid}를 받지 않는다. */
    public record GetSubInfo(@JsonProperty("token") String token) {
    }

    /** 3.7 성적 저장. */
    public record SaveStdTest(@JsonProperty("token") String token,
                              @JsonProperty("rsv_cd") String rsvCd,
                              @JsonProperty("score_type") String scoreType,
                              @JsonProperty("subject") Integer subject,
                              @JsonProperty("score") String score) {
    }

    /** 3.8 성적표 파일. */
    public record SaveStdFile(@JsonProperty("token") String token,
                              @JsonProperty("rsv_cd") String rsvCd,
                              @JsonProperty("file") String file) {
    }

    /** 3.9(신규) — 첨부 목록. */
    public record GetStdFile(@JsonProperty("token") String token,
                             @JsonProperty("rsv_cd") String rsvCd) {
    }

    /** 3.10(신규) — 첨부 내려받기 주소. ★ rsv_cd 를 함께 받아 소유를 확인한다. */
    public record GetStdFileUrl(@JsonProperty("token") String token,
                                @JsonProperty("rsv_cd") String rsvCd,
                                @JsonProperty("file_no") Long fileNo) {
    }
}
