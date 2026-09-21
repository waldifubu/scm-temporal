package com.supplychainmanagement.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how ShipmentPackage.items is mapped. A PackageItem outlives its package - loose items exist
 * without one, and an item taken out of a package goes back to being loose - so the collection must
 * never delete what leaves it. Read from the annotation, because a unit test cannot see what
 * Hibernate would do at flush; the Spring context tests boot the mapping itself.
 */
class ShipmentPackageMappingTest {

    private static OneToMany itemsMapping() throws NoSuchFieldException {
        return ShipmentPackage.class.getDeclaredField("items").getAnnotation(OneToMany.class);
    }

    /** Removing an item from the list must not delete the row - its packed quantity would vanish. */
    @Test
    void takingAnItemOutOfAPackageDoesNotDeleteIt() throws NoSuchFieldException {
        assertThat(itemsMapping().orphanRemoval()).isFalse();
    }

    /** Neither must deleting a package take its items with it - ALL includes REMOVE. */
    @Test
    void deletingAPackageDoesNotDeleteItsItems() throws NoSuchFieldException {
        assertThat(itemsMapping().cascade()).doesNotContain(CascadeType.REMOVE, CascadeType.ALL);
    }

    /** A package created together with its items still saves them in one go. */
    @Test
    void savingAPackageStillSavesItsNewItems() throws NoSuchFieldException {
        assertThat(itemsMapping().cascade()).contains(CascadeType.PERSIST, CascadeType.MERGE);
    }
}
