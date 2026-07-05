package com.example.supplychainmanagement.entity.usertypes;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("supplier")
public class Supplier extends User {
}
