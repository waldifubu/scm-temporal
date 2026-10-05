package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.supplychainmanagement.support.TestData;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code request_components.qty} is a {@code bigint} - the type {@code RequestComponent.qty}
 * has claimed all along.
 * <p>
 * It was an {@code int(11)}: the entity's type was widened and {@code ddl-auto=update} never follows
 * a type change. With {@code STRICT_TRANS_TABLES} the server then refused an out-of-range quantity
 * at the insert, so a mistyped number came back as a 500 - and anyone reading the entity would have
 * assumed a {@code Long} was storable. {@link RequestComponentQtyMigration} widens it on startup,
 * and {@code @SpringBootTest} runs that before this test writes.
 * <p>
 * A property of the schema, so no unit test can see it. Needs a reachable database and rolls back.
 */
@SpringBootTest
@Import(TestData.class)
@ActiveProfiles("test")
@Transactional
class RequestComponentQtyTest {

    @Autowired
    private TestData testData;
    @Autowired
    private RequestComponentRepository requestComponentRepository;
    @Autowired
    private ComponentRepository componentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesTheQuantityAsABigint() {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'request_components'
                  AND column_name = 'qty'
                """, String.class);

        assertThat(dataType).isEqualToIgnoringCase("bigint");
    }

    /**
     * The point of the widening, rather than the column type for its own sake: a value above
     * {@code Integer.MAX_VALUE} goes in and comes back unchanged. On the old {@code int(11)} the
     * insert was refused outright.
     * <p>
     * Nothing may order this much - {@code RequestComponentsRequest.MAX_QTY} caps a request at a
     * million, and the goods receipt refuses anything that does not fit an {@code int}. What is held
     * here is that the column no longer silently decides the range for them.
     */
    @Test
    void takesAQuantityBeyondTheIntRange() {
        Component component = testData.component();
        Supplier supplier = testData.supplier();

        RequestComponent request = new RequestComponent();
        request.setComponent(component);
        request.setSupplier(supplier);
        request.setQty(3_000_000_000L);

        RequestComponent saved = requestComponentRepository.saveAndFlush(request);

        assertThat(saved.getQty()).isEqualTo(3_000_000_000L);
        assertThat(requestComponentRepository.findById(saved.getId()))
                .get()
                .satisfies(stored -> assertThat(stored.getQty()).isEqualTo(3_000_000_000L));
    }
}
