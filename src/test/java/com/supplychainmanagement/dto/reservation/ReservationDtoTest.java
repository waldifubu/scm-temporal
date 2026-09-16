package com.supplychainmanagement.dto.reservation;

import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Storehouse;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state the release endpoint answers from: a reservation whose LAZY references are proxies of a
 * session that has already closed, which is what the REQUIRES_NEW inventory layer hands back.
 * <p>
 * Reproduced with {@code getReference} inside a transaction that ends before serialization - no
 * row is read or written, so the test does not depend on the data. It does depend on the database
 * being reachable, like {@link com.supplychainmanagement.ApplicationTests}.
 */
@SpringBootTest
class ReservationDtoTest {

    private static final Long UNKNOWN_ID = -1L;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper jsonMapper;

    private Reservation detached;

    @BeforeEach
    void reservationFromAClosedSession() {
        detached = new TransactionTemplate(transactionManager).execute(status -> Reservation.active(
                entityManager.getReference(OrderItem.class, UNKNOWN_ID),
                String.valueOf(UNKNOWN_ID),
                UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"),
                3,
                entityManager.getReference(Storehouse.class, UNKNOWN_ID)));

        assertThat(Hibernate.isInitialized(detached.getOrderItem())).isFalse();
    }

    /** Why the DTO exists - pinned so that nobody hands the entity back to the endpoint. */
    @Test
    void theEntityCannotBeSerializedOnceItsSessionIsClosed() {
        assertThatThrownBy(() -> jsonMapper.writeValueAsString(detached))
                .hasMessageContaining("no session");
    }

    @Test
    void theDtoReadsTheIdsWithoutInitializingTheProxies() {
        ReservationDto dto = ReservationDto.of(detached);

        assertThat(dto.orderItemId()).isEqualTo(UNKNOWN_ID);
        assertThat(dto.storehouseId()).isEqualTo(UNKNOWN_ID);
        assertThat(Hibernate.isInitialized(detached.getOrderItem())).isFalse();
        assertThat(Hibernate.isInitialized(detached.getStorehouse())).isFalse();
    }

    @Test
    void theDtoSerializes() {
        String json = jsonMapper.writeValueAsString(ReservationDto.of(detached));

        assertThat(json)
                .contains("\"orderItemId\":-1")
                .contains("\"storehouseId\":-1")
                .contains("\"quantity\":3")
                .contains("\"status\":\"ACTIVE\"");
    }

    /** Rows from before order_item_id existed carry no line and must not break the mapping. */
    @Test
    void aReservationWithoutAnOrderItemMapsToANullId() {
        detached.setOrderItem(null);

        assertThat(ReservationDto.of(detached).orderItemId()).isNull();
    }
}
