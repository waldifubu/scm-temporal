package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A list row carries its items as DTOs. The PackageItem entity points back to its package, and
 * serializing it ran in circles package -> items -> package - the row must name the package by id.
 */
class ShipmentPackageListDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static ShipmentPackage packageWithOneItem() {
        Product product = new Product();
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));

        OrderItem line = new OrderItem();
        line.setId(11L);
        line.setQuantity(10);
        line.setProduct(product);
        Order order = new Order();
        order.setOrderNo(1042L);
        line.setOrder(order);

        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setId(5L);
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.OPEN);

        PackageItem item = new PackageItem();
        item.setId(101L);
        item.setOrderItem(line);
        item.setQuantity(4);
        item.setShipmentPackage(shipmentPackage);
        shipmentPackage.getItems().add(item);

        return shipmentPackage;
    }

    @Test
    void listsTheItemsAsDtos() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(packageWithOneItem());

        assertThat(row.items()).extracting(ShipmentPackageItemDto::id).containsExactly(101L);
        assertThat(row.items()).extracting(ShipmentPackageItemDto::orderNo).containsExactly(1042L);
        assertThat(row.packages()).isEqualTo(1);
    }

    /** Serializes in one go, and the item does not repeat its package - neither nested nor by id. */
    @Test
    void serializesWithoutThePackageInsideItsItems() {
        JsonNode json = mapper.valueToTree(ShipmentPackageListDto.from(packageWithOneItem()));

        JsonNode item = json.get("items").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(101L);
        assertThat(item.has("shipmentPackageId")).isFalse();
        assertThat(item.has("shipmentPackage")).isFalse();
        assertThat(item.has("orderItem")).isFalse();
    }
}
