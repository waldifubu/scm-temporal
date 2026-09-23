package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional fields of a shipment are left out of the JSON while they are null - the address is
 * optional when a shipment is created. Uses a plain mapper, like the running application.
 * <p>
 * Built through {@link ShipmentResponse#from}, not the canonical constructor: the record grows a
 * field now and then, and a positional call here would have to be counted out again every time.
 */
class ShipmentResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** A shipment without packages, so the response carries only the shipment's own data. */
    private String jsonOf(String shippingAddress) {
        Customer customer = new Customer();
        customer.setId(3L);
        customer.setFirstName("Ada");
        customer.setLastName("Lovelace");

        Shipment shipment = new Shipment();
        shipment.setId(50L);
        shipment.setCustomer(customer);
        shipment.setStatus(ShipmentStatus.CREATED);
        shipment.setShippingAddress(shippingAddress);

        return mapper.writeValueAsString(ShipmentResponse.from(shipment, List.of(), null));
    }

    @Test
    void leavesOutAMissingAddress() {
        String json = jsonOf(null);

        assertThat(json).doesNotContain("shippingAddress");
        assertThat(json).doesNotContain("distributorId");
        assertThat(json).contains("\"status\":\"CREATED\"");
        assertThat(json).contains("\"customerName\":\"Ada Lovelace\"");
    }

    @Test
    void showsAnAddressThatIsSet() {
        assertThat(jsonOf("Musterstr. 1")).contains("\"shippingAddress\":\"Musterstr. 1\"");
    }

    /** A shipment without packages answers an empty list, not a missing key. */
    @Test
    void alwaysCarriesThePackagesList() {
        assertThat(jsonOf("Musterstr. 1")).contains("\"packages\":[]");
    }
}
