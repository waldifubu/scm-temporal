package com.supplychainmanagement.dto.fullfillment;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPageResponseTest {

    private static ProductionResultDto result(boolean produced) {
        return new ProductionResultDto("product", UUID.randomUUID(), 1L, produced, "");
    }

    /** produced counts the built products; total stays the number of products paged over. */
    @Test
    void countsTheProducedNextToThePageFields() {
        var page = new PageImpl<>(List.of(result(true), result(true), result(false)), PageRequest.of(0, 100), 150);

        var response = ProductionPageResponse.of(page);

        assertThat(response.produced()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(150);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.content()).hasSize(3);
    }

    @Test
    void producedIsZeroForAnEmptyPage() {
        var page = new PageImpl<ProductionResultDto>(List.of(), PageRequest.of(0, 100), 150);

        assertThat(ProductionPageResponse.of(page).produced()).isZero();
    }
}
