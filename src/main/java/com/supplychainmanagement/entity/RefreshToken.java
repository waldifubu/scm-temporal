package com.supplychainmanagement.entity;

import com.supplychainmanagement.entity.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One refresh token a browser holds, as the server remembers it: <strong>only the hash</strong> of the value
 * (a database dump must not hand out sessions), the user, and where it stands in its family.
 * <p>
 * A <em>family</em> is the chain of tokens that grew out of one login: every refresh retires the token it was
 * given ({@code usedAt}) and issues the next one into the same family. A token that is shown again after it
 * was retired can only be a copy somebody kept, so the whole family is revoked - see
 * {@code RefreshTokenService}. {@code familyExpiresAt} is the absolute end of the chain: however busy the
 * session is, it does not outlive its first login by more than that.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"),
        indexes = {
                @Index(name = "ix_refresh_token_family", columnList = "family_id"),
                @Index(name = "ix_refresh_token_user", columnList = "user_id")
        }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 of the token, hex. The token itself is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(nullable = false)
    private Instant issuedAt;

    /** End of this token's own life: the idle window from when it was issued, never past {@link #familyExpiresAt}. */
    @Column(nullable = false)
    private Instant expiresAt;

    /** Absolute end of the family, set at login and carried unchanged from token to token. */
    @Column(nullable = false)
    private Instant familyExpiresAt;

    /** When a refresh retired this token; {@code null} while it is the live one. */
    private Instant usedAt;

    /** When the family was called off (logout, reuse, password change); {@code null} while it stands. */
    private Instant revokedAt;
}
