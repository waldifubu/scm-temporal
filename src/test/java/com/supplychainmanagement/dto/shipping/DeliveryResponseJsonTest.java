package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the carrier is told about a shipment: enough to read its page - whose it is, when it was made, the
 * planner's note - and still nothing of what is in the packages.
 * <p>
 * Built through {@link DeliveryResponse#from}, not the canonical constructor: the record grows a field
 * now and then, and a positional call here would have to be counted out again every time.
 */
class DeliveryResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private Shipment shipment(String comment) {
        Customer customer = new Customer();
        customer.setId(312L);
        customer.setFirstName("Cuddy");
        customer.setLastName("Customer Toyota");

        Shipment shipment = new Shipment();
        shipment.setId(50L);
        shipment.setCustomer(customer);
        shipment.setStatus(ShipmentStatus.DISPATCH_REQUESTED);
        shipment.setCreatedAt(LocalDateTime.of(2026, 9, 22, 15, 20, 4));
        shipment.setComment(comment);
        return shipment;
    }

    private JsonNode jsonOf(Shipment shipment) {
        return mapper.readTree(mapper.writeValueAsString(DeliveryResponse.from(shipment)));
    }

    @Test
    void carriesTheCustomersIdTheCreationAndTheNote() {
        DeliveryResponse delivery = DeliveryResponse.from(shipment("Please call first"));

        assertThat(delivery.customerId()).isEqualTo(312L);
        assertThat(delivery.customerName()).isEqualTo("Cuddy Customer Toyota");
        assertThat(delivery.createdAt()).isEqualTo(LocalDateTime.of(2026, 9, 22, 15, 20, 4));
        assertThat(delivery.comment()).isEqualTo("Please call first");
    }

    @Test
    void leavesTheNoteOutOfTheJsonWhileThereIsNone() {
        JsonNode json = jsonOf(shipment(null));

        assertThat(json.has("comment")).isFalse();
        assertThat(json.get("customerId").asLong()).isEqualTo(312L);
        assertThat(json.has("createdAt")).isTrue();
    }

    @Test
    void stillAnswersNothingOfThePackageContents() {
        JsonNode json = jsonOf(shipment("x"));

        assertThat(json.has("packages")).isFalse();
        assertThat(json.has("items")).isFalse();
        assertThat(json.has("packageNumbers")).isTrue();
    }

    @Test
    void copesWithAShipmentWithoutACustomer() {
        Shipment shipment = shipment(null);
        shipment.setCustomer(null);

        DeliveryResponse delivery = DeliveryResponse.from(shipment);

        assertThat(delivery.customerId()).isNull();
        assertThat(delivery.customerName()).isNull();
    }

    /** When the shipment last changed, next to when it was made and when it left. */
    @Test
    void carriesWhenTheShipmentLastChangedAndWhenItLeft() {
        Shipment shipment = shipment("x");
        shipment.setUpdatedAt(LocalDateTime.of(2026, 10, 25, 9, 30, 0));
        shipment.setShippedAt(LocalDateTime.of(2026, 10, 24, 8, 0, 0));
        shipment.setTrackingNumber("DHL-123");

        DeliveryResponse delivery = DeliveryResponse.from(shipment);

        assertThat(delivery.updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 9, 30, 0));
        assertThat(delivery.shippedAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 8, 0, 0));
        assertThat(delivery.trackingNumber()).isEqualTo("DHL-123");
        assertThat(jsonOf(shipment).has("updatedAt")).isTrue();
    }

    @Test
    void leavesTheUpdateOutOfTheJsonWhileThereIsNone() {
        assertThat(jsonOf(shipment("x")).has("updatedAt")).isFalse();
    }
}
