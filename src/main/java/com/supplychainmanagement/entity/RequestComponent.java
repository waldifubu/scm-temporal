package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RequestStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@RequiredArgsConstructor
@AllArgsConstructor
@Table(name = "request_components")
public class RequestComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne
    private Component component;

    @Positive(message = "Quantity must be a minimum of 1")
    private Long qty;

    @CreationTimestamp
    private LocalDateTime requestDate;

    @JsonIgnore
    @ManyToOne
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    private RequestStatus requestStatus;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String comment;

    @UpdateTimestamp
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime updated;

    /**
     * Who last moved this request on - set with every status change, next to {@code updated}, which
     * Hibernate stamps itself.
     * <p>
     * Not a history, one line of it: the request's own statuses say where it has been, this says who
     * put it there last. A full audit trail would be a table of its own, like {@code OrderHistory}.
     * <p>
     * <strong>Nullable</strong>, and that is deliberate: rows placed before this column existed have
     * nobody to name, and a caller the user lookup cannot resolve - a sweep, a system job - must not
     * fail the step it is doing. Same reasoning as {@code OrderHistory.user_id}. A {@code User} and
     * not a {@code Supplier}: the supplier's three steps and the warehouse's receipt both write it,
     * so every role that touches a request has to fit.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    private User assignedBy;

    @PrePersist
    public void prePersist() {
        if (requestStatus == null) {
            requestStatus = RequestStatus.OPEN;
        }
    }
}
