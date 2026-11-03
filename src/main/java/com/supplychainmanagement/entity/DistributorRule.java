package com.supplychainmanagement.entity;

import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row of the table that says which carrier a shipment goes to: the step after the fixed customer
 * to distributor map in {@code UserServiceImpl}. A rule names a {@link #distributor} and the conditions
 * under which it applies; a condition left {@code null} is not one - it matches everything.
 * <p>
 * Dimensions (all optional): the {@link #customer}, a weight range and a volume range. A bound is
 * inclusive at the bottom and exclusive at the top, {@code [min, max)}, so two neighbouring rules
 * (up to 50 kg, from 50 kg) never both claim 50. The most specific matching rule wins - every
 * dimension that is set counts one - and {@link #priority} only decides between rules of the same
 * specificity.
 * <p>
 * Units are those of the shipment: weight in kg, volume in cm&sup3; (length &times; width &times;
 * height of the packages, as {@code ShipmentPackage.getVolume()} works it out, not the m&sup3; the
 * pages show).
 */
@Entity
@Table(name = "distributor_rule")
@Getter
@Setter
@NoArgsConstructor
public class DistributorRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The carrier the rule hands a shipment to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "distributor_id", nullable = false)
    private Distributor distributor;

    /** Only for this customer; {@code null} for any customer. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** Shipment weight in kg, from (inclusive); {@code null}: no lower bound. */
    @Column(precision = 19, scale = 4)
    private BigDecimal minWeight;

    /** Shipment weight in kg, up to (exclusive); {@code null}: no upper bound. */
    @Column(precision = 19, scale = 4)
    private BigDecimal maxWeight;

    /** Shipment volume in cm&sup3;, from (inclusive); {@code null}: no lower bound. */
    @Column(precision = 19, scale = 4)
    private BigDecimal minVolume;

    /** Shipment volume in cm&sup3;, up to (exclusive); {@code null}: no upper bound. */
    @Column(precision = 19, scale = 4)
    private BigDecimal maxVolume;

    /** Breaks a tie between rules that are equally specific; the higher one wins. */
    @Column(nullable = false)
    private int priority = 0;

    /**
     * How many dimensions the rule sets: the customer, a weight range, a volume range (a range counts once,
     * whether one bound or both are given). A rule that asks more of a shipment is the more specific one.
     */
    public int specificity() {
        return (customer != null ? 1 : 0)
                + (minWeight != null || maxWeight != null ? 1 : 0)
                + (minVolume != null || maxVolume != null ? 1 : 0);
    }
}
