package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
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
    @GeneratedValue(strategy = GenerationType.UUID)
    @UuidGenerator
    private UUID id;

    @JsonIgnore
    @ManyToOne
    private Component component;

    @Transient
    private String sku;

    private int qty;

    private LocalDateTime requestDate;

    @JsonIgnore
    @ManyToOne
    private User supplier;

    @Enumerated(EnumType.STRING)
    private RequestStatus requestStatus = RequestStatus.OPEN;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String comment;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime updated;
}
