package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class ShipmentController {

    final private ShipmentPackageRepository shipmentPackageRepository;

    @GetMapping(path = "/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public PageResponse<ShipmentPackageListDto> getPackages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
//            @RequestParam(defaultValue = "ACTIVE") ReservationStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        var paackages = shipmentPackageRepository.findAll(pageable);
        var packageResponses = paackages.map(ShipmentPackageListDto::from);

        return PageResponse.of(packageResponses);
    }


//    Filter: Order = CONVEYABLE

//    GET /api/v1/shipments?status=READY
/*
    {
        "content": [
        {
            "shipmentId": "SHIP-20001",
                "orderId": 8,
                "status": "READY",
                "storehouseId": 1,
                "destination": {
            "name": "Muster GmbH",
                    "street": "Hauptstraße 10",
                    "postalCode": "50667",
                    "city": "Köln",
                    "country": "DE"
        },
            "packageCount": 2,
                "weight": 12.5
        }
  ],
        "page": 0,
            "size": 20,
            "totalElements": 1,
            "totalPages": 1
    }
    */


//    POST /api/v1/shipments/SHIP-20001/dispatch
}
