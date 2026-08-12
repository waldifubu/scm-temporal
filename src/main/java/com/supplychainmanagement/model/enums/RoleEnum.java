package com.supplychainmanagement.model.enums;

import java.util.Arrays;

public enum RoleEnum {
    CUSTOMER,
    MANAGER,
    SUPPLIER,
    WAREHOUSE,
    LOGISTICS,
    DISTRIBUTOR,
    ADMIN;

//    public final String label;
/*
    RoleEnum(String label) {
        this.label = label;
    }
*/

    public static boolean contains(String s) {
        return Arrays.stream(values()).anyMatch(choice -> choice.name().equalsIgnoreCase(s));
    }

    public static RoleEnum valueOfLabel(String label) {
        for (RoleEnum e : values()) {
            if (e.name().equalsIgnoreCase(label)) {
                return e;
            }
        }
        return null;
    }
}
