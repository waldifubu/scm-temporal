package com.supplychainmanagement.dto.component;

import com.supplychainmanagement.entity.users.Supplier;

/**
 * A supplier as someone placing a component request needs to see them: which id to put in the path
 * of {@code POST /components/request/&#123;supplierId&#125;}, and a name to pick them by. Nothing else.
 * <p>
 * Deliberately not the {@code UserDto}: that one carries the e-mail address, the user name and the
 * roles, and the warehouse - which is allowed to order components but not to read {@code /users} -
 * has no business with any of it. The list exists because it had no other way to learn an id.
 */
public record SupplierResponse(Long id, String name) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), RequestComponentResponse.nameOf(supplier));
    }
}
