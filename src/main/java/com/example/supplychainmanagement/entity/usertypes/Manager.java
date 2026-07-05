package com.example.supplychainmanagement.entity.usertypes;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("manager")
public class Manager extends User {
}
