package com.supplychainmanagement.controller;

import com.supplychainmanagement.exception.APIException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Three query parameters that used to end as a 500: a sort that names no property, a negative page
 * and a size below one. Nothing handles the exceptions behind them, so each was a server error for
 * what is a typo in a query string.
 */
class PageRequestsTest {

    private static final Set<String> SORTABLE = Set.of("id", "name");

    private static void assertBadRequest(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void buildsThePageRequestItWasGiven() {
        Pageable pageable = PageRequests.of(2, 50, "name", "DESC", SORTABLE);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "name"));
    }

    /** Anything but DESC is ascending - including a direction nobody has heard of. */
    @Test
    void readsTheDirectionLeniently() {
        assertThat(PageRequests.of(0, 10, "id", "asc", SORTABLE).getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(PageRequests.of(0, 10, "id", "sideways", SORTABLE).getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(PageRequests.of(0, 10, "id", "desc", SORTABLE).getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
    }

    /** The message names the parameter and what is allowed - a caller can fix it from that alone. */
    @Test
    void refusesASortFieldThatIsNotListed() {
        Throwable thrown = catchThrowable(() -> PageRequests.of(0, 10, "password", "ASC", SORTABLE));

        assertBadRequest(thrown);
        assertThat(thrown).hasMessageContaining("password").hasMessageContaining("id").hasMessageContaining("name");
    }

    /** PageRequest.of throws IllegalArgumentException for this, which nothing handles. */
    @Test
    void refusesANegativePage() {
        assertBadRequest(catchThrowable(() -> PageRequests.of(-1, 10, "id", "ASC", SORTABLE)));
    }

    @Test
    void refusesASizeBelowOne() {
        assertBadRequest(catchThrowable(() -> PageRequests.of(0, 0, "id", "ASC", SORTABLE)));
        assertBadRequest(catchThrowable(() -> PageRequests.of(0, -5, "id", "ASC", SORTABLE)));
    }

    /** The first page is page 0, and one row is a size. */
    @Test
    void acceptsTheSmallestValidValues() {
        Pageable pageable = PageRequests.of(0, 1, "id", "ASC", SORTABLE);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }
}
