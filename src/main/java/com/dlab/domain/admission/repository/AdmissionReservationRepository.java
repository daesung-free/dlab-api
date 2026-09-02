package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionReservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionReservationRepository extends JpaRepository<AdmissionReservation, Long> {

    Optional<AdmissionReservation> findByRsvCdAndDeletedFalse(String rsvCd);

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
}
