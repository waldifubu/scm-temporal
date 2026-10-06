package com.supplychainmanagement.controller;

import com.supplychainmanagement.exception.APIException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code page / size / sort / order} of a list endpoint, checked before it reaches Spring Data.
 * <p>
 * Three request parameters used to end as a 500, because nothing handles the exceptions behind them:
 * a {@code sort} that names no property ({@code PropertyReferenceException}), a negative {@code page}
 * and a {@code size} below one ({@code IllegalArgumentException} from {@code PageRequest.of}). All
 * three are a typo in a query string, so all three are a 400 that says which parameter and what is
 * allowed.
 * <p>
 * {@code sort} is applied as a property path, which is why it is checked against a list the endpoint
 * names rather than against the entity: a path into a collection or a join is a valid property and
 * still not something a caller should be able to sort a page by.
 */
final class PageRequests {

    private PageRequests() {
    }

    static Pageable of(int page, int size, String sort, String order, Set<String> sortable) {
        if (page < 0) {
            throw new APIException(HttpStatus.BAD_REQUEST, "page cannot be negative, got " + page);
        }
        if (size < 1) {
            throw new APIException(HttpStatus.BAD_REQUEST, "size has to be at least 1, got " + size);
        }
        if (!sortable.contains(sort)) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "Cannot sort by '" + sort + "' - one of " + new TreeSet<>(sortable));
        }

        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
}
