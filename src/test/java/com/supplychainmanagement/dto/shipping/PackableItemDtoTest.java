package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remaining quantity is worked out in the DTO, not selected: the query would otherwise repeat its
 * sub-select for it. The constructor the projection calls therefore has to get the arithmetic right.
 */
class PackableItemDtoTest {

    private static PackableItemDto line(int quantity, long packed) {
        return new PackableItemDto(1L, 2L, 104321L, 9L, "Regal", quantity, packed,
                FulfillmentStatus.PACKING, 3L, "Lager Nord", null);
    }

    @Test
    void remainingIsWhatIsNotPackedYet() {
        PackableItemDto dto = line(10, 4);

        assertThat(dto.quantity()).isEqualTo(10);
        assertThat(dto.packed()).isEqualTo(4);
        assertThat(dto.remaining()).isEqualTo(6);
    }

    @Test
    void aLineNothingIsPackedOfRemainsInFull() {
        assertThat(line(7, 0).remaining()).isEqualTo(7);
    }

    /** A line packed in full has nothing left - and would not be listed: packLine has set it PACKED by then. */
    @Test
    void aFullyPackedLineRemainsZero() {
        assertThat(line(7, 7).remaining()).isZero();
    }

    @Test
    void carriesTheConsumedReservationAndWhereItWasPickedFrom() {
        PackableItemDto dto = line(5, 0);

        assertThat(dto.reservationId()).isEqualTo(2L);
        assertThat(dto.storehouseId()).isEqualTo(3L);
        assertThat(dto.storehouseName()).isEqualTo("Lager Nord");
    }
}
