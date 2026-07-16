package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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

    private String description;

    @ColumnDefault("0.0")
    private Double weight;

    @Column(unique = true)
    private String externalId;

    @Column(nullable = true, unique = true)
    private UUID sku;

    @PrePersist
    void applyDefaultStatus() {
        if (this.weight == null || this.weight == 0) {
            this.weight = 10.0 + new Random().nextDouble() * 20; // Set a random weight value between 10 and 30
        }
    }
}
