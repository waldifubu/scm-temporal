package com.supplychainmanagement.repository;

import com.supplychainmanagement.dto.stock.InventoryRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stock added up per SKU. Not a Spring Data interface, on purpose: the page is sorted by sums, and a
 * derived sort ({@code order by s.onHand}) names a column that is not in the {@code group by}. The sort key is
 * mapped here to the expression it means; nothing the caller sent reaches the query text.
 */
@Repository
@RequiredArgsConstructor
public class StockInventoryRepository {

    /** The sort keys a client may name, and what each orders by. */
    static final Map<String, String> ORDER_EXPRESSIONS = Map.of(
            "sku", "s.sku",
            "name", "p.name",
            "onHand", "sum(s.onHand)",
            "reserved", "sum(s.reserved)",
            "available", "(sum(s.onHand) - sum(s.reserved))",
            "updatedAt", "max(s.updatedAt)");

    public static final String DEFAULT_SORT = "sku";

    private final EntityManager entityManager;

    /**
     * One page, one row per SKU. The product's name is joined on the SKU (a left join: stock whose SKU no
     * product carries is still stock). The SKU is the last sort key, so rows with the same sum keep their place
     * from one page to the next.
     */
    public Page<InventoryRow> findInventory(Pageable pageable) {
        Sort.Order requested = pageable.getSort().stream().findFirst().orElse(Sort.Order.asc(DEFAULT_SORT));
        String expression = ORDER_EXPRESSIONS.get(requested.getProperty());
        if (expression == null) {
            throw new IllegalArgumentException("Cannot sort the inventory by " + requested.getProperty());
        }
        String direction = requested.isDescending() ? "desc" : "asc";

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createQuery(
                        "select s.sku, p.name, sum(s.onHand), sum(s.reserved), max(s.updatedAt) "
                                + "from Stock s left join Product p on p.sku = s.sku "
                                + "group by s.sku, p.name "
                                + "order by " + expression + " " + direction + ", s.sku asc")
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        List<InventoryRow> content = rows.stream().map(StockInventoryRepository::toRow).toList();
        long total = entityManager.createQuery("select count(distinct s.sku) from Stock s", Long.class).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    private static InventoryRow toRow(Object[] row) {
        long onHand = ((Number) row[2]).longValue();
        long reserved = ((Number) row[3]).longValue();
        return new InventoryRow((UUID) row[0], (String) row[1], onHand, reserved, onHand - reserved, (LocalDateTime) row[4]);
    }
}
