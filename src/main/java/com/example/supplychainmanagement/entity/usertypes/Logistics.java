package com.example.supplychainmanagement.entity.usertypes;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("logistics")
public class Logistics extends User {
}
