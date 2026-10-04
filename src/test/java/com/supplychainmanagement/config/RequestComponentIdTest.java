package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import org.hibernate.Hibernate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a component request can actually be inserted - that is, that {@code request_components.id}
 * generates itself.
 * <p>
 * It did not: the table was created while {@code RequestComponent.id} was a {@code UUID}, so the
 * column is of type {@code uuid} with no {@code AUTO_INCREMENT}, while the entity has since become a
 * {@code Long} with {@code IDENTITY}. Hibernate therefore left the column out of the insert and
 * MariaDB answered {@code Field 'id' doesn't have a default value} - the endpoint was unusable.
 * {@link RequestComponentIdMigration} converts the column on startup, and {@code @SpringBootTest}
 * runs it before this test writes.
 * <p>
 * A property of the schema, so no unit test can see it - {@code ComponentServiceRequestTest} mocks
 * the repository away and passes either way. Needs a reachable database and rolls back.
 */
@SpringBootTest
@Transactional
class RequestComponentIdTest {

    @Autowired
    private RequestComponentRepository requestComponentRepository;
    @Autowired
    private ComponentRepository componentRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void generatesTheKeyOfAComponentRequest() {
        Component component = componentRepository.findAll().getFirst();
        Supplier supplier = userRepository.findAll().stream()
                .map(Hibernate::unproxy)
                .filter(Supplier.class::isInstance)
                .map(Supplier.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no supplier in the database to request from"));

        RequestComponent request = new RequestComponent();
        request.setComponent(component);
        request.setSupplier(supplier);
        request.setQty(12L);
        request.setComment("key generation");

        RequestComponent saved = requestComponentRepository.saveAndFlush(request);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRequestStatus()).isEqualTo(RequestStatus.OPEN);
    }

    /** Two in a row get two different keys - an auto-increment, not a constant default. */
    @Test
    void countsTheKeyUp() {
        Component component = componentRepository.findAll().getFirst();
        User supplier = userRepository.findAll().stream()
                .map(Hibernate::unproxy)
                .filter(Supplier.class::isInstance)
                .map(User.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no supplier in the database to request from"));

        Long first = saveOne(component, (Supplier) supplier).getId();
        Long second = saveOne(component, (Supplier) supplier).getId();

        assertThat(second).isGreaterThan(first);
    }

    private RequestComponent saveOne(Component component, Supplier supplier) {
        RequestComponent request = new RequestComponent();
        request.setComponent(component);
        request.setSupplier(supplier);
        request.setQty(1L);
        return requestComponentRepository.saveAndFlush(request);
    }
}
