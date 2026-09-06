package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.entity.ShipmentPackage;

public interface PackingService {
    ShipmentPackage createPackage(Long orderNo, CreatePackageRequest createPackageRequest);

    PickingOrderDto packingReservationByIdComplete(Long reservationId);

//    public void  createPackage();
//    public void completePackage();
}
