package com.supplychainmanagement.entity;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//    @Column(nullable = false, unique = true)
//    private String name;
//    @Column(name = "product_status", nullable = true)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    protected RoleEnum rolename;

    @JsonIgnore
    @ManyToMany(mappedBy = "roles")
    private List<User> users;
}
