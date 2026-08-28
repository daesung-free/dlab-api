package com.dlab.domain.admission.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.common.storage.FileStorage;
import com.dlab.domain.admission.entity.AdmissionFile;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.AdmissionScore;
import com.dlab.domain.admission.entity.CommonCode;
import com.dlab.domain.admission.repository.AdmissionFileRepository;
import com.dlab.domain.admission.repository.AdmissionReservationRepository;
import com.dlab.domain.admission.repository.AdmissionScoreRepository;
import com.dlab.domain.admission.repository.CommonCodeRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈페이지 입학예약 (규격서 3.3~3.8).
 *
 * <p>홈페이지가 지금까지 대성전산을 호출하던 자리에 우리가 앉는다 — 상대는 주소만 바꾼다.
 *
 * <h2>지점은 요청의 {@code acid}가 정한다</h2>
 * 키오스크는 토큰이 지점을 정하지만 여기는 자격증명이 전 지점 공통이라 요청마다 온다.
 * <b>알파벳 코드({@code F}/{@code I}/{@code T}…)라 키오스크 {@code acad_cd}와 다른 체계다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionReservationService {

    /** 성적표 최대 크기. 규격이 Base64 문자열이라 본문 전체가 메모리에 올라온다. */
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private static final int RSV_CD_RETRY = 5;

    private final AdmissionReservationRepository reservationRepository;
    private final AdmissionScoreRepository scoreRepository;
    private final AdmissionFileRepository fileRepository;
    private final CommonCodeRepository commonCodeRepository;
    private final AcademyRepository academyRepository;
    private final FileStorage fileStorage;

    /** 저장 요청 한 건. 필드가 규격서 3.3 그대로다 — 홈페이지가 보내는 것을 고를 수 없다. */
    public record SaveCommand(String acid, String regYyyy, String rsvNm,
                              String stdTel, String parTel, String genderGb, String birth,
                              Short geyulGb, String admDt, Integer preTest, Integer admiSt,
                              Short nasinSt, java.math.BigDecimal nasinSc,
                              String uniNm, Short uniGd, Short intrSt, String intrTxt,
                              Integer findGb, String findTxt, Integer schCd,
                              String zip, String addr1, String addr2,
                              Integer schCdHigh, String schNmHigh,
                              String agreeAd, String promoAd, String stdGrade) {
    }

    // ── 3.3 원생 정보 저장 ────────────────────────────────────

    /**
     * 입학예약 저장. {@code rsv_cd}를 발급해 돌려준다.
     *
     * <p><b>중복 신청을 막지 않는다.</b> 같은 사람이 전형을 바꿔 다시 넣는 일이 실제로
     * 있고, 규격서 조회(3.4)도 배열을 돌려주게 돼 있다 — 여러 건이 정상이다.
     */
    @Transactional
    public String save(SaveCommand command) {
        Academy academy = requireAcademy(command.acid());
        short year = parseYear(command.regYyyy());

        if (isBlank(command.rsvNm()) || isBlank(command.stdTel()) || isBlank(command.stdGrade())) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }

        AdmissionReservation reservation = AdmissionReservation.builder()
                .academy(academy)
                .year(year)
                .rsvCd(newRsvCd())
                .studentName(command.rsvNm())
                .studentTel(command.stdTel())
                .parentTel(command.parTel())
                .gender(command.genderGb())
                .birth(command.birth())
                .geyulGb(command.geyulGb())
                .admDt(command.admDt())
                .preTest(command.preTest())
                .admiSt(command.admiSt())
                .findGb(command.findGb())
                .findTxt(command.findTxt())
                .schCd(command.schCd())
                .nasinSt(command.nasinSt())
                .nasinSc(command.nasinSc())
                .uniNm(command.uniNm())
                .uniGd(command.uniGd())
                .intrSt(command.intrSt())
                .intrTxt(command.intrTxt())
                .zip(command.zip())
                .addr1(command.addr1())
                .addr2(command.addr2())
                .schCdHigh(command.schCdHigh())
                .schNmHigh(command.schNmHigh())
                .agreeAd("Y".equalsIgnoreCase(command.agreeAd()))
                .promoAd("Y".equalsIgnoreCase(command.promoAd()))
                .stdGrade(command.stdGrade())
                .build();

        return reservationRepository.save(reservation).getRsvCd();
    }

    // ── 3.4 원생 정보 조회 ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdmissionReservation> search(String acid, Integer preTest, String rsvNm,
                                             String birth, String stdTel) {
        Academy academy = requireAcademy(acid);
        return reservationRepository.search(academy.getId(), preTest, rsvNm, birth, stdTel);
    }

    // ── 3.5 · 3.6 코드 조회 ───────────────────────────────────

    /**
     * 공통코드. ⚠️ <b>값 목록을 아직 못 받아 비어 있다</b> — 홈페이지 드롭다운이 빈 채로 뜬다.
     */
    @Transactional(readOnly = true)
    public List<CommonCode> commonCodes(String acid, String grp) {
        Academy academy = requireAcademy(acid);
        if (isBlank(grp)) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        return commonCodeRepository.findByGroup(grp.toUpperCase(), academy.getId());
    }

    /** 과목. 3.6은 {@code acid}를 받지 않는다 — 전 지점 공통이다. */
    @Transactional(readOnly = true)
    public List<CommonCode> subjects() {
        return commonCodeRepository.findAllOfGroup(CommonCode.GRP_SUBJECT);
    }

    // ── 3.7 성적 저장 ─────────────────────────────────────────

    /**
     * 지원기준 성적. <b>같은 과목·구분을 다시 보내면 덮어쓴다</b> — 홈페이지에서 수정 후
     * 재전송하는 흐름이 있어서다.
     */
    @Transactional
    public void saveScore(String rsvCd, String scoreType, Integer subject, String score) {
        if (isBlank(scoreType) || subject == null || isBlank(score)) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        AdmissionReservation reservation = requireReservation(rsvCd);

        scoreRepository.findByReservationIdAndScoreTypeAndSubjectAndDeletedFalse(
                        reservation.getId(), scoreType, subject)
                .ifPresentOrElse(
                        existing -> existing.changeScore(score),
                        () -> scoreRepository.save(
                                new AdmissionScore(reservation, scoreType, subject, score)));
    }

    // ── 3.8 성적표 파일 ───────────────────────────────────────

    /**
     * 성적표 저장. 요청이 {@code data:image/png;base64,...} Data URL이다.
     *
     * <p><b>본문은 DB에 넣지 않는다</b> — 수 MB짜리가 행에 들어가면 백업·복제 비용이
     * 그대로 늘어난다. 저장소에 넣고 위치만 남긴다.
     */
    @Transactional
    public void saveFile(String rsvCd, String dataUrl) {
        AdmissionReservation reservation = requireReservation(rsvCd);
        if (isBlank(dataUrl)) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }

        String contentType = null;
        String base64 = dataUrl;
        int comma = dataUrl.indexOf(',');
        if (dataUrl.startsWith("data:") && comma > 0) {
            String header = dataUrl.substring(5, comma);
            contentType = header.replace(";base64", "");
            base64 = dataUrl.substring(comma + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            log.warn("성적표 디코딩 실패: rsvCd={}", rsvCd);
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }

        String key = "admission/%s/%d".formatted(rsvCd, System.nanoTime());
        String stored = fileStorage.put(key, bytes, contentType);

        fileRepository.save(new AdmissionFile(reservation, contentType, bytes.length, stored));
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 학원코드 → 지점.
     *
     * <p>홈페이지는 알파벳({@code F}/{@code I}/{@code T}…)을 보낸다. 키오스크의
     * {@code acad_cd}(31·32…)와 <b>다른 체계</b>라 서로 섞으면 엉뚱한 지점에 저장된다.
     */
    private Academy requireAcademy(String acid) {
        if (isBlank(acid)) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        return academyRepository.findByDlabCdAndDeletedFalse(acid.toUpperCase())
                .orElseThrow(() -> new DsaApiException(
                        DsaCode.INVALID_PARAMETER, "알 수 없는 학원코드입니다."));
    }

    private AdmissionReservation requireReservation(String rsvCd) {
        if (isBlank(rsvCd)) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
        return reservationRepository.findByRsvCdAndDeletedFalse(rsvCd)
                .orElseThrow(() -> new DsaApiException(
                        DsaCode.INVALID_PARAMETER, "예약 정보를 찾을 수 없습니다."));
    }

    /**
     * 학생고유코드 발급.
     *
     * <p><b>순번을 쓰지 않는다</b> — 외부에 나가는 값이라 연속이면 지원자 수가 추정되고,
     * 남의 코드를 넘겨짚어 조회할 수 있다.
     */
    private String newRsvCd() {
        for (int attempt = 0; attempt < RSV_CD_RETRY; attempt++) {
            String candidate = String.valueOf(ThreadLocalRandom.current().nextLong(
                    1_000_000_0L, 10_000_000_0L));
            if (!reservationRepository.existsByRsvCd(candidate)) {
                return candidate;
            }
        }
        throw new DsaApiException(DsaCode.INVALID_PARAMETER, "코드 발급에 실패했습니다.");
    }

    private short parseYear(String regYyyy) {
        try {
            return Short.parseShort(regYyyy);
        } catch (NumberFormatException | NullPointerException e) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
