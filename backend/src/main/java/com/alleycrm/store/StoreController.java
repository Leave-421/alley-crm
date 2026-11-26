package com.alleycrm.store;

import com.alleycrm.account.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 권역의 참여 가게 목록 · 상인회 위치 수정(C6) · 사장님 내 가게 변경(C22). 계약: CONTRACTS_API 「가게 목록 · 위치」 · 「사장님 내 가게」. */
@RestController
class StoreController {

    private final StoreService stores;

    StoreController(StoreService stores) {
        this.stores = stores;
    }

    @GetMapping("/api/v1/regions")
    Map<String, List<RegionResponse>> regions() {
        return Map.of("items", stores.regions());
    }

    @GetMapping("/api/v1/stores")
    Map<String, List<StoreResponse>> list(@RequestParam @Min(1) long regionId) {
        return Map.of("items", stores.participatingStores(regionId).stream().map(StoreResponse::of).toList());
    }

    @GetMapping("/api/v1/owner/store")
    StoreResponse myStore(@AuthenticationPrincipal AccountPrincipal me) {
        return StoreResponse.of(stores.myStore(me));
    }

    @PutMapping("/api/v1/owner/store/status")
    StoreResponse changeStatus(@AuthenticationPrincipal AccountPrincipal me, @Valid @RequestBody OwnerStoreStatusRequest body) {
        return StoreResponse.of(stores.changeStatus(me, body.businessStatus(), body.reservationEnabled()));
    }

    @PutMapping("/api/v1/owner/store/hours")
    StoreResponse changeHours(@AuthenticationPrincipal AccountPrincipal me, @Valid @RequestBody OwnerStoreHoursRequest body) {
        return StoreResponse.of(stores.changeHours(me, body));
    }

    @PutMapping("/api/v1/stores/{storeId}/location")
    StoreResponse updateLocation(@AuthenticationPrincipal AccountPrincipal me, @PathVariable long storeId,
            @Valid @RequestBody StoreLocationRequest body) {
        return StoreResponse.of(stores.updateLocation(me, storeId, body.address(), body.alley(), body.mapX(), body.mapY()));
    }
}
