package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.util.Random;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "components")
public class Component {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    //@JsonIgnoreProperties("components") //Product wíthout components will be displayed
    @JsonIgnore
    private Product product;

    private String manufacturer;

    private String name;

    private String articleNo;

    private String description;

    @ColumnDefault("0.0")
    @Column(precision = 10, scale = 3)
    private BigDecimal weight;

    @Column(unique = true)
    private String externalId;

    @Column(nullable = false, unique = true)
    private UUID sku;

    /**
     * How many of this component go into one unit of the product - the bill-of-materials quantity.
     * <p>
     * A Component row belongs to exactly one product (product_id is NOT NULL), so it is a recipe line
     * and not a shared catalogue part - which is why the quantity sits here and not on a join table.
     * Never null and never below 1: a line that is part of the recipe is needed at least once, and a
     * 0 would tell ProductionServiceImpl the product can be built out of nothing.
     */
    @ColumnDefault("1")
    @Column(nullable = false)
    private Integer qty;

    @PrePersist
    void onCreate() {
        // Same idea as the weight below: a value that cannot be meant stands in for the default
        // rather than reaching the NOT NULL column or, worse, the recipe.
        if (this.qty == null || this.qty < 1) {
            this.qty = 1;
        }
        if (this.weight == null || this.weight.compareTo(BigDecimal.ZERO) == 0) {
            this.weight = BigDecimal.valueOf(10.0 + new Random().nextDouble() * 20); // Set a random weight value between 10 and 30
        }
    }
}
