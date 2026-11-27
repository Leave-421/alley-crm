package com.alleycrm.settlement;

import com.alleycrm.reservation.owner.OwnerStoreResolver;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 사장님 방문 금액 정정(D22, FR-STL-09). 늦은 방문 처리(D23)는 OwnerReservationController의 late-visit */
@RestController
class OwnerCorrectionController {

    private final OwnerStoreResolver owners;
    private final VisitAmountCorrectionService corrections;

    OwnerCorrectionController(OwnerStoreResolver owners, VisitAmountCorrectionService corrections) {
        this.owners = owners;
        this.corrections = corrections;
    }

    @PostMapping("/api/v1/owner/reservations/{id}/amount-correction")
    VisitAmountCorrectionService.Correction correct(@PathVariable long id, @RequestBody CorrectionRequest body,
            Authentication auth) {
        return corrections.correct(owners.storeIdOf(auth), id, body == null ? null : body.correctedAmount());
    }

    record CorrectionRequest(Integer correctedAmount) {
    }
}
