package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionReservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionReservationRepository extends JpaRepository<AdmissionReservation, Long> {

    Optional<AdmissionReservation> findByRsvCdAndDeletedFalse(String rsvCd);

    /** 같은 신청이 다시 들어왔는지. 재전송은 같은 {@code requestId} 로 온다. */
    Optional<AdmissionReservation> findByRequestIdAndDeletedFalse(String requestId);

    boolean existsByRsvCd(String rsvCd);

    /**
     * 조회(규격서 3.4) — 지점·전형·이름·생년월일·연락처.
     *
     * <p>규격이 배열을 돌려주게 돼 있다. 같은 사람이 여러 번 신청할 수 있어서다.
     */
    @Query("""
            SELECT r FROM AdmissionReservation r
            WHERE r.academy.id = :academyId
              AND r.studentName = :studentName
              AND r.birth = :birth
              AND r.studentTel = :studentTel
              AND (:preTest IS NULL OR r.preTest = :preTest)
              AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<AdmissionReservation> search(@Param("academyId") Long academyId,
                                      @Param("preTest") Integer preTest,
                                      @Param("studentName") String studentName,
                                      @Param("birth") String birth,
                                      @Param("studentTel") String studentTel);

    /**
     * 같은 이름의 <b>아직 전환되지 않은</b> 입학예약 — 중복 탐지 후보 (피드백 6-3①).
     *
     * <p>★ <b>생년월일·연락처 일치는 호출부에서 본다.</b> 규격서 필드라 생년이
     * {@code yyyyMMdd} 문자열이고 연락처에는 하이픈이 섞여 있어, SQL 로 비교하면
     * {@code 010-1234-5678} 과 {@code 01012345678} 이 다른 번호가 된다. 숫자만 뽑아
     * 비교해야 하는데 그 변환을 DB 함수로 넣으면 인덱스를 못 타고 방언에 묶인다 —
     * 후보가 "같은 지점·같은 연도·같은 이름"이라 많아야 몇 건이므로 Java 에서 걸러도
     * 비용이 없다({@code StudentEnrollmentRepository.findRecentByName}와 같은 판단).
     *
     * <p><b>전환된 건은 후보가 아니다.</b> 이미 학생이 된 예약을 또 가리키면 화면이
     * "그 예약을 전환" 버튼을 띄우는데 그 버튼은 눌려도 막힌다.
     */
    @Query("""
            SELECT r FROM AdmissionReservation r
            WHERE r.academy.id = :academyId
              AND r.year = :year
              AND r.studentName = :studentName
              AND r.enrollment IS NULL
              AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<AdmissionReservation> findUnconvertedByName(@Param("academyId") Long academyId,
                                                     @Param("year") short year,
                                                     @Param("studentName") String studentName);

    /**
     * 관리자 목록 (F-4.2-1).
     *
     * <p>★ <b>지점은 파라미터가 아니라 권한에서 온다.</b> 요청 값으로 받으면 바꿔 보내는
     * 것만으로 다른 지점 신청자가 새어나간다(§7).
     *
     * @param academyId 전 지점 권한자면 {@code null} 을 넘겨 지점 조건을 뺀다
     * @param keyword   <b>이미 {@code %} 가 붙은 패턴</b>이다. 검색어가 없으면 {@code "%"} —
     *                  {@code null} 을 넘기면 PostgreSQL 이 {@code bytea} 로 추론해
     *                  <i>operator does not exist</i> 로 깨진다
     */
    @Query("""
            SELECT r FROM AdmissionReservation r
            WHERE r.year = :year
              AND r.deleted = false
              AND (:academyId IS NULL OR r.academy.id = :academyId)
              AND (:status IS NULL OR r.consultStatus = :status)
              AND (r.studentName LIKE :keyword OR r.studentTel LIKE :keyword)
            ORDER BY r.id DESC
            """)
    List<AdmissionReservation> search(Long academyId, short year,
                                      com.dlab.domain.admission.entity.ConsultStatus status,
                                      String keyword);
}
