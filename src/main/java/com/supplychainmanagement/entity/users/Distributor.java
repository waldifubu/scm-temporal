package com.supplychainmanagement.entity.users;


import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

@Entity
@DiscriminatorValue("distributor")
@Getter
@Setter
public class Distributor extends User {
    @Transient
    Long ruleId; // <-- The rule that matched a shipment to this distributor, if any. Not persisted.
}
