package com.example.supplychainmanagement.entity.usertypes;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("warehouse")
public class Warehouse extends User {
}
