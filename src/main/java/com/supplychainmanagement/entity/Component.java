package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

    @Column(nullable = true, unique = true)
    private String sku;
    @Column(nullable = false, unique = true)
    private String articleNo;
}
