package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ShipmentPackage.complete(): OPEN to PACKED, once. */
class ShipmentPackageCompleteTest {

    /** A package in the given status holding one item. */
    private static ShipmentPackage inStatus(ShipmentPackageStatus status) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setShipmentPackageStatus(status);
        shipmentPackage.getItems().add(new PackageItem());
        return shipmentPackage;
    }

    @Test
    void closesAnOpenPackageAndStampsIt() {
        ShipmentPackage shipmentPackage = inStatus(ShipmentPackageStatus.OPEN);

        shipmentPackage.complete();

        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.PACKED);
        assertThat(shipmentPackage.getPackedAt()).isNotNull();
    }

    /**
     * Not OPEN any more: complete() throws an IllegalStateException - which GlobalExceptionHandler
     * would answer with a 500. PackingServiceImpl.completePackage therefore checks the status first
     * and answers 409.
     */
    @Test
    void refusesAPackageThatIsNotOpen() {
        ShipmentPackage shipmentPackage = inStatus(ShipmentPackageStatus.PACKED);

        assertThatThrownBy(shipmentPackage::complete).isInstanceOf(IllegalStateException.class);
        assertThat(shipmentPackage.getPackedAt()).isNull();
    }

    /** Never without items - the service answers 400 before it gets here. */
    @Test
    void refusesAnEmptyPackage() {
        ShipmentPackage shipmentPackage = inStatus(ShipmentPackageStatus.OPEN);
        shipmentPackage.getItems().clear();

        assertThatThrownBy(shipmentPackage::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no items");
        assertThat(shipmentPackage.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.OPEN);
    }
}
