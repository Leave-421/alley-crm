package com.alleycrm.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    boolean existsByReservationNo(String reservationNo);
}
