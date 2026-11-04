package com.supplychainmanagement.entity;

import com.supplychainmanagement.entity.users.Supplier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * One row of the table that says which supplier a component request goes to - the counterpart of
 * {@link DistributorRule} for the inbound side. A rule names a {@link #supplier} and the conditions
 * under which it applies: a {@link #component}, a {@link #product}, both or neither. A condition left
 * {@code null} is not one - it matches everything.
 */
@Entity
@Table(name = "supplier_rule")
@Getter
@Setter
@NoArgsConstructor
public class SupplierRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Only for this component; {@code null} for any component. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id")
    private Component component;

    /** Only for components of this product; {@code null} for any product. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** The supplier the rule hands a request to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /** Breaks a tie between rules that are equally specific; the higher one wins. */
    @ColumnDefault("0")
    @Column(nullable = false)
    private int priority = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * How specific the rule is: naming a component weighs more than naming a product, so a rule for one
     * part beats a rule for its whole product, and a rule naming both beats either. A rule that names
     * neither is a catch-all and ranks last.
     */
    public int specificity() {
        return (component != null ? 2 : 0) + (product != null ? 1 : 0);
    }

    /** Whether the rule applies to this component: every condition it sets has to hold. */
    public boolean appliesTo(Component candidate) {
        boolean componentFits = component == null || component.getId().equals(candidate.getId());
        boolean productFits = product == null
                || (candidate.getProduct() != null && product.getId().equals(candidate.getProduct().getId()));
        return componentFits && productFits;
    }

    /** Best rule first: more specific, then higher priority, then the older (lower id) one. */
    public static final Comparator<SupplierRule> BEST_FIRST =
            Comparator.comparingInt(SupplierRule::specificity).reversed()
                    .thenComparing(Comparator.comparingInt(SupplierRule::getPriority).reversed())
                    .thenComparing(SupplierRule::getId, Comparator.nullsLast(Comparator.naturalOrder()));
}
