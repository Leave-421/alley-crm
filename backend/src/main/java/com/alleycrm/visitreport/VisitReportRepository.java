package com.alleycrm.visitreport;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface VisitReportRepository extends JpaRepository<VisitReport, Long> {

    List<VisitReport> findByCustomerIdOrderByCreatedAtDescIdDesc(long customerId);

    boolean existsByReservationIdAndType(long reservationId, String type);

    boolean existsByReservationIdAndTypeAndResult(long reservationId, String type, String result);
}
