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
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

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
    private RequestStatus requestStatus = RequestStatus.OPEN;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String comment;

    @UpdateTimestamp
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime updated;

    @PrePersist
    public void prePersist() {
        if (requestStatus == null) {
            requestStatus = RequestStatus.OPEN;
        }
    }
}
