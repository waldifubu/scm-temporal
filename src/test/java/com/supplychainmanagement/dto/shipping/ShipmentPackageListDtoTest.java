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

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    private static ShipmentPackage withDimensions(String length, String width, String height) {
        ShipmentPackage shipmentPackage = packageWithOneItem();
        shipmentPackage.setLength(length == null ? null : new BigDecimal(length));
        shipmentPackage.setWidth(width == null ? null : new BigDecimal(width));
        shipmentPackage.setHeight(height == null ? null : new BigDecimal(height));
        return shipmentPackage;
    }

    /** Each dimension is its own, not read off another: three different numbers cannot be swapped unseen. */
    @Test
    void carriesLengthWidthAndHeight() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(withDimensions("120.5", "80", "15"));

        assertThat(row.length()).isEqualByComparingTo("120.5");
        assertThat(row.width()).isEqualByComparingTo("80");
        assertThat(row.height()).isEqualByComparingTo("15");
    }

    /** Volume is the product of the three, so the row is consistent with what it shows. */
    @Test
    void showsADimensionedPackagesVolumeNextToThem() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(withDimensions("4", "3", "2"));

        assertThat(row.volume()).isEqualByComparingTo("24");
    }

    /** A package without dimensions shows none - null, not an invented zero. */
    @Test
    void leavesADimensionNullThatThePackageHasNone() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(withDimensions(null, null, null));

        assertThat(row.length()).isNull();
        assertThat(row.width()).isNull();
        assertThat(row.height()).isNull();
        assertThat(row.volume()).isEqualByComparingTo("0");
    }

    private static ShipmentPackage madeAt(LocalDateTime createdAt, LocalDateTime packedAt) {
        ShipmentPackage shipmentPackage = packageWithOneItem();
        shipmentPackage.setCreatedAt(createdAt);
        shipmentPackage.setPackedAt(packedAt);
        return shipmentPackage;
    }

    /** Two different moments, so one cannot be read off the other. */
    @Test
    void carriesWhenThePackageWasMadeAndWhenItWasPacked() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(
                madeAt(LocalDateTime.of(2026, 10, 7, 9, 15, 30), LocalDateTime.of(2026, 10, 8, 14, 5, 0)));

        assertThat(row.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 9, 15, 30));
        assertThat(row.packedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 14, 5, 0));
    }

    /** An OPEN package was made and has not been packed: createdAt is there, packedAt is not. */
    @Test
    void hasNoPackedAtUntilThePackageIsCompleted() {
        ShipmentPackageListDto row = ShipmentPackageListDto.from(madeAt(LocalDateTime.of(2026, 10, 7, 9, 15, 30), null));

        assertThat(row.createdAt()).isNotNull();
        assertThat(row.packedAt()).isNull();
    }

    /** The pattern of the items in the same row, and a null stays a null in the JSON. */
    @Test
    void serializesBothMomentsLikeTheItemsOfTheSameRow() {
        JsonNode json = mapper.valueToTree(ShipmentPackageListDto.from(
                madeAt(LocalDateTime.of(2026, 10, 7, 9, 15, 30), null)));

        assertThat(json.get("createdAt").asText()).isEqualTo("2026-10-07 09:15:30");
        assertThat(json.has("packedAt")).isTrue();
        assertThat(json.get("packedAt").isNull()).isTrue();
    }

    @Test
    void serializesTheDimensionsUnderTheirOwnNames() {
        JsonNode json = mapper.valueToTree(ShipmentPackageListDto.from(withDimensions("120.5", "80", "15")));

        assertThat(json.get("length").decimalValue()).isEqualByComparingTo("120.5");
        assertThat(json.get("width").decimalValue()).isEqualByComparingTo("80");
        assertThat(json.get("height").decimalValue()).isEqualByComparingTo("15");
        assertThat(json.has("volume")).isTrue();
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
