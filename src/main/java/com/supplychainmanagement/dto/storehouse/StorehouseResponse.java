package com.supplychainmanagement.dto.storehouse;

import com.supplychainmanagement.entity.Storehouse;

/**
 * One storehouse as the API lists it - the identity a client needs to name it.
 * <p>
 * A DTO and not the {@code Storehouse} entity, for the usual reason: it carries a {@code stocks}
 * collection, and a stock row points back at its storehouse. Left out here entirely, the list costs
 * one query and cannot start a cycle. What a storehouse <em>holds</em> is
 * {@code GET /stock/storehouse/&#123;id&#125;}.
 * <p>
 * It exists because two warehouse operations take a {@code storehouseId} and nothing said which ids
 * there are: the goods receipt ({@code POST /components/warehouse/&#123;requestId&#125;/in-stock/&#123;storehouseId&#125;})
 * and booking stock ({@code POST /stock/add}). A client had to be told the ids out of band.
 */
public record StorehouseResponse(
        Long id,
        String name,
        String address,
        String city,
        String country
) {

    public static StorehouseResponse from(Storehouse storehouse) {
        return new StorehouseResponse(
                storehouse.getId(),
                storehouse.getName(),
                storehouse.getAddress(),
                storehouse.getCity(),
                storehouse.getCountry()
        );
    }
}
