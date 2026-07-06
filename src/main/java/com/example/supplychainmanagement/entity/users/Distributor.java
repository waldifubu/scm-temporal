package com.example.supplychainmanagement.entity.users;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("distributor")
public class Distributor extends User {
}
