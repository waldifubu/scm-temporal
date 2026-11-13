package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.model.enums.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequestComponentRepository extends JpaRepository<RequestComponent, Long> {

    /**
     * Locks the request for the rest of the transaction. Its status is read, checked and then
     * written, so two reports arriving at once would otherwise both pass the same check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RequestComponent r WHERE r.id = :id")
    Optional<RequestComponent> findForUpdateById(@Param("id") Long id);

    /**
     * The supplier's own requests, with what the response reads fetched along - the component for
     * its SKU and name, the acting user for their name. Without the graph this was a query per row
     * and per level, resolved during serialization through the open-in-view session.
     */
    @EntityGraph(attributePaths = {"component", "assignedBy"})
    List<RequestComponent> findBySupplierId(Long userId);

    /**
     * The same list, newest first: {@code requestDate} descending, and the id after it because requests
     * placed in one go share their date (the supplier's own list is not paged, but the order should still be
     * the same on every call).
     */
    @EntityGraph(attributePaths = {"component", "assignedBy"})
    List<RequestComponent> findBySupplierIdOrderByRequestDateDescIdDesc(Long userId);

    /**
     * The supplier's list without the requests of one status: what is {@code IN_STOCK} is booked and
     * done - there is nothing left for the supplier to answer - so it drops out of their view. Newest
     * first, like the list above.
     */
    @EntityGraph(attributePaths = {"component", "assignedBy"})
    List<RequestComponent> findBySupplierIdAndRequestStatusNotOrderByRequestDateDescIdDesc(Long userId, RequestStatus status);

    /**
     * One page of requests, newest state of each - the work list behind
     * {@code GET /components/requests}.
     * <p>
     * {@code component} and {@code assignedBy} come with it: the response reads the component's SKU
     * and name and the user's name, which initializes both proxies - one query per row without the
     * graph. {@code supplier} is deliberately left out, because only its id is read and a proxy
     * answers {@code getId()} unloaded. Both are to-one associations, so the graph is a join and the
     * page is still paged in SQL - unlike a collection fetch, see the package lists. The supplier comes
     * along as well: the answer names it, and a proxy would cost a query per row.
     */
    @EntityGraph(attributePaths = {"component", "component.product", "assignedBy", "supplier"})
    Page<RequestComponent> findAllBy(Pageable pageable);

    /** The same page narrowed to one status - {@code DELIVERED} is what the warehouse works off. */
    @EntityGraph(attributePaths = {"component", "component.product", "assignedBy", "supplier"})
    Page<RequestComponent> findAllByRequestStatus(RequestStatus status, Pageable pageable);
}
