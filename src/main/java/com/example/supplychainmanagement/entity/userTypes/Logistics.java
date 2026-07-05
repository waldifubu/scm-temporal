package com.example.supplychainmanagement.entity.userTypes;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("logistics")
public class Logistics extends User {
}
