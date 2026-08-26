package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.model.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderIdAndSkuAndStorehouseIdAndStatus(
            String orderId,
            UUID sku,
            Long storehouseId,
            ReservationStatus status);

    default Optional<Reservation> findActive(
            String orderId,
            UUID sku,
            Long storehouseId) {

        return findByOrderIdAndSkuAndStorehouseIdAndStatus(
                orderId,
                sku,
                storehouseId,
                ReservationStatus.ACTIVE);
    }

    default List<Reservation> findActive(String orderId) {

        return findByOrderIdAndStatus(
                orderId,
                ReservationStatus.ACTIVE);
    }

    List<Reservation> findByOrderIdAndStatus(
            String orderId,
            ReservationStatus status);
}
