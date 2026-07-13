package com.supplychainmanagement.entity.users;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("logistics")
public class Logistics extends User {
}
