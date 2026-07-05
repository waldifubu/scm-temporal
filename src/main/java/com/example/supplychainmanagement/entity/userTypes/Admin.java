package com.example.supplychainmanagement.entity.userTypes;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("admin")
public class Admin extends User {

}
