package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.model.enums.ShipmentStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optional fields of a shipment are left out of the JSON while they are null - the address is
 * optional when a shipment is created. Uses a plain mapper, like the running application.
 */
class ShipmentResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static ShipmentResponse withAddress(String shippingAddress) {
        return new ShipmentResponse(50L, 3L, "Ada Lovelace", ShipmentStatus.CREATED, shippingAddress,
                null, null, null, null, null, null, 0, BigDecimal.ZERO, null, null, List.of());
    }

    @Test
    void leavesOutAMissingAddress() {
        String json = mapper.writeValueAsString(withAddress(null));

        assertThat(json).doesNotContain("shippingAddress");
        assertThat(json).doesNotContain("distributorId");
        assertThat(json).contains("\"status\":\"CREATED\"");
    }

    @Test
    void showsAnAddressThatIsSet() {
        String json = mapper.writeValueAsString(withAddress("Musterstr. 1"));

        assertThat(json).contains("\"shippingAddress\":\"Musterstr. 1\"");
    }
}
