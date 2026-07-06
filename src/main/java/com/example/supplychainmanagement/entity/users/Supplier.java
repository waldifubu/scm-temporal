package com.example.supplychainmanagement.entity.users;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("supplier")
public class Supplier extends User {
}
