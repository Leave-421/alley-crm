package com.alleycrm.reservation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByReservationNo(String reservationNo);

    /** 상태를 바꾸기 전에 행을 잠근다(사장님 처리와 자동 작업이 겹쳐도 한 번만 바뀜) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findForUpdate(@Param("id") long id);
}
