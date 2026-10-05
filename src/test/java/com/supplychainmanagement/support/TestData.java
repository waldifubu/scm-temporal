package com.supplychainmanagement.support;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rows a context test needs, created by the test itself.
 * <p>
 * The context tests used to take them from whatever happened to be in the database
 * ({@code componentRepository.findAll().getFirst()}), which only worked because they ran against the
 * development database - the very thing having a test schema is meant to stop. An empty schema made
 * every one of them fail, and a changed one could make them fail at any time.
 * <p>
 * Everything here is created inside the calling test's transaction and rolls back with it, so a test
 * leaves nothing behind and two runs see the same thing. Unique columns get a random or counted
 * value for that reason: {@code users.email}, {@code users.username},
 * {@code products.article_no}, {@code products.sku} and {@code components.sku} are all unique, and
 * rows from a test that did <em>not</em> roll back must not collide with the next run.
 */
@TestComponent
@RequiredArgsConstructor
public class TestData {

    /** Enough to keep article numbers apart within one run; the random part covers across runs. */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000L);

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ComponentRepository componentRepository;
    private final StorehouseRepository storehouseRepository;

    private static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A customer, as an order needs one. */
    public Customer customer() {
        Customer customer = new Customer();
        fillUser(customer, "Ada", "Lovelace");
        return userRepository.saveAndFlush(customer);
    }

    /** A supplier, as a component request needs one. */
    public Supplier supplier() {
        Supplier supplier = new Supplier();
        fillUser(supplier, "Sam", "Supply");
        return userRepository.saveAndFlush(supplier);
    }

    /**
     * {@code createdAt} is written by hand: the column is {@code nullable = false} and there is no
     * lifecycle callback filling it, so an insert without it fails.
     */
    private void fillUser(com.supplychainmanagement.entity.users.User user, String first, String last) {
        user.setFirstName(first);
        user.setLastName(last);
        user.setEmail(unique("test") + "@example.invalid");
        user.setUsername(unique(first.toLowerCase()));
        user.setPassword("{noop}irrelevant-for-tests");
        user.setIsActive(true);
        user.setCreatedAt(LocalDateTime.now());
    }

    /**
     * A product. {@code sku} and {@code createdAt} are set here - the entity's {@code @PrePersist}
     * only computes the weight from the components and does not fill either, and both columns are
     * NOT NULL.
     */
    public Product product() {
        Product product = new Product();
        product.setArticleNo(SEQUENCE.incrementAndGet());
        product.setName("Regal");
        product.setDescription("created by TestData");
        product.setUnitPrice(new BigDecimal("19.99"));
        product.setWeight(BigDecimal.ONE);
        product.setSku(UUID.randomUUID());
        product.setCreatedAt(LocalDateTime.now());
        return productRepository.saveAndFlush(product);
    }

    /** A component of a fresh product - {@code component.product_id} is NOT NULL. */
    public Component component() {
        return component(product());
    }

    /** A component of the given product, with a recipe quantity of 1. */
    public Component component(Product product) {
        Component component = new Component();
        component.setProduct(product);
        component.setName("Blech");
        component.setManufacturer("ACME");
        component.setArticleNo(unique("A"));
        component.setSku(UUID.randomUUID());
        component.setQty(1);
        component.setWeight(BigDecimal.ONE);
        return componentRepository.saveAndFlush(component);
    }

    /** A storehouse to book stock into. */
    public Storehouse storehouse() {
        Storehouse storehouse = new Storehouse();
        storehouse.setName(unique("Lager"));
        storehouse.setAddress("Musterstr. 1");
        storehouse.setCity("Berlin");
        storehouse.setCountry("DE");
        return storehouseRepository.saveAndFlush(storehouse);
    }
}
