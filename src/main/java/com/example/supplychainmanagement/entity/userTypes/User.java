package com.example.supplychainmanagement.entity.userTypes;

import com.example.supplychainmanagement.entity.Role;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Builder
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email", name = "unique_email_constraint")
        }
)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "user_type", discriminatorType = DiscriminatorType.STRING)
public class User implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String firstName;
    @Column(nullable = false)
    private String lastName;
    @Email(message = "Email address seems not to be valid")
    @Column(nullable = false,  unique = true)
    private String email;
    @Column(nullable = false,   unique = true)
    private String username;
    @JsonIgnore
    @Column(nullable = false)
    private String password;
    @Column(name = "user_type", insertable = false, updatable = false)
    private String userType;
    @JsonFormat(pattern = "dd.MM.yyyy HH:mm:ss")
    private LocalDateTime lastLogin;
    @CreationTimestamp()
    private LocalDateTime createdAt;
    @UpdateTimestamp()
    private LocalDateTime updatedAt;
    @Column(nullable = false)
    private boolean isActive = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "users_roles",
            joinColumns = {@JoinColumn(name = "user_id", referencedColumnName = "id")},
            inverseJoinColumns = {@JoinColumn(name = "role_id", referencedColumnName = "id")})
    private Set<Role> roles;
}
