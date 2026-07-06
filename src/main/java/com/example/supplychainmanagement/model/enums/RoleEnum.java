package com.example.supplychainmanagement.model.enums;

import java.util.Arrays;

public enum RoleEnum {
    ROLE_CUSTOMER("customer"),
    ROLE_MANAGER("manager"),
    ROLE_SUPPLIER("supplier"),
    ROLE_WAREHOUSE("warehouse"),
    ROLE_LOGISTICS("logistics"),
    ROLE_DISTRIBUTOR("distributor"),
    ROLE_ADMIN("admin");

    public final String label;

    RoleEnum(String label) {
        this.label = label;
    }

    public static boolean contains(String s) {
        return Arrays.stream(values()).anyMatch(choice -> choice.name().equalsIgnoreCase(s));
    }

    public static RoleEnum valueOfLabel(String label) {
        for (RoleEnum e : values()) {
            if (e.label.equals(label)) {
                return e;
            }
        }
        return null;
    }

    public static RoleEnum fromNameOrLabel(String value) {
        if (value == null) {
            return null;
        }

        for (RoleEnum e : values()) {
            if (e.name().equalsIgnoreCase(value) || e.label.equalsIgnoreCase(value)) {
                return e;
            }
        }

        return null;
    }
}
