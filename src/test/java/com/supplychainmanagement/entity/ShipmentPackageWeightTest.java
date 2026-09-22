package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ShipmentPackageType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The package weight is always the weight of the contents - there is no declared weight. */
class ShipmentPackageWeightTest {

    private static PackageItem item(String unitWeight, int quantity) {
        Product product = new Product();
        product.setWeight(unitWeight == null ? null : new BigDecimal(unitWeight));

        OrderItem line = new OrderItem();
        line.setProduct(product);

        PackageItem item = new PackageItem();
        item.setOrderItem(line);
        item.setQuantity(quantity);
        return item;
    }

    @Test
    void weighsNothingWithoutItems() {
        ShipmentPackage shipmentPackage = new ShipmentPackage();

        shipmentPackage.onCreate();

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Set on insert from the items, unit weight times quantity; a product without weight adds 0. */
    @Test
    void takesTheWeightOfItsContentsOnInsert() {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.getItems().add(item("1.5", 2));
        shipmentPackage.getItems().add(item("0.25", 4));
        shipmentPackage.getItems().add(item(null, 3));

        shipmentPackage.onCreate();

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo("4.0");
    }

    /** Adding an item through the package updates the weight at once - no save needed. */
    @Test
    void addItemAddsTheWeightOfTheItem() {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        PackageItem item = item("1.5", 2);

        shipmentPackage.addItem(item);
        shipmentPackage.addItem(item("0.5", 1));

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo("3.5");
        assertThat(item.getShipmentPackage()).isSameAs(shipmentPackage);
    }

    /** Taking the last item out leaves 0, and the item is loose again - not deleted. */
    @Test
    void removeItemTakesTheWeightOffAgain() {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        PackageItem item = item("2", 3);
        shipmentPackage.addItem(item);

        shipmentPackage.removeItem(item);

        assertThat(shipmentPackage.getWeight()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(shipmentPackage.getItems()).isEmpty();
        assertThat(item.getShipmentPackage()).isNull();
    }

    /** The gross weight adds the packaging on top of the computed content weight. */
    @Test
    void addsTheTareForThePackageWeight() {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        shipmentPackage.addItem(item("2", 1));

        assertThat(shipmentPackage.getPackageWeight())
                .isEqualByComparingTo(new BigDecimal("2").add(ShipmentPackageType.CARTON.getTareWeight()));
    }
}
