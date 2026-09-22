package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A new shipment starts as CREATED - set on insert, not by the service. */
class ShipmentPrePersistTest {

    @Test
    void startsAsCreated() {
        Shipment shipment = new Shipment();

        shipment.onCreate();

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void keepsAStatusThatIsAlreadySet() {
        Shipment shipment = new Shipment();
        shipment.setStatus(ShipmentStatus.READY);

        shipment.onCreate();

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.READY);
    }
}
