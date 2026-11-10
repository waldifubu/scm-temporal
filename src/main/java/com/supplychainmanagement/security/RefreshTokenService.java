package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.RefreshToken;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Refresh tokens: a long-lived credential that can only buy a new short-lived one, kept apart from the access
 * token so that the part that travels with every request can be short and the part that can be revoked is rare.
 * <p>
 * <strong>Opaque and stored.</strong> The token is 256 random bits; the database holds its SHA-256 only. A
 * signed token (a JWT) would need no table, but it can be neither revoked nor retired, which is what a
 * credential that lives for days has to be.
 * <p>
 * <strong>Rotation with reuse detection.</strong> Every refresh retires the token it was given and issues
 * the next one into the same family. Showing a retired token again means somebody kept a copy: the whole
 * family is revoked, the thief's chain and the owner's alike, and the owner logs in again. A short grace
 * window ({@code app.refresh.graceSeconds}) keeps two tabs refreshing at the same moment from looking like
 * theft - within it a retired token is accepted once more and simply forks the chain.
 * <p>
 * <strong>Two ends.</strong> A token lives {@code idleDays} from when it was issued; the family ends
 * {@code maxDays} after the login that began it, whatever the activity.
 */
@Slf4j
@Service
public class RefreshTokenService {

    /** Same words for every way a refresh token can fail, so the answer says no and nothing more. */
    static final String INVALID = "Invalid refresh token";

    /** Retired rows are kept this long for the reuse check, then swept. */
    private static final Duration KEEP_USED = Duration.ofDays(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;

    /** The time source; a test moves it instead of waiting for a token to age. */
    private Clock clock = Clock.systemUTC();

    @Value("${app.refresh.cookie.name:scmrefresh}")
    private String cookieName;

    /** Scoped to the auth space so the browser sends it nowhere else; versioned like the endpoint it serves. */
    @Value("${app.refresh.cookie.path:/api/${spring.mvc.apiversion.default:1.0}/auth}")
    private String cookiePath;

    @Value("${app.cookie.secure:false}")
    private boolean secure;

    @Value("${app.refresh.idleDays:7}")
    private long idleDays;

    @Value("${app.refresh.maxDays:30}")
    private long maxDays;

    @Value("${app.refresh.graceSeconds:20}")
    private long graceSeconds;

    public RefreshTokenService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * A freshly issued token and what the access token that goes with it needs to know about the user. Read
     * inside the transaction: the roles are lazy, and the caller has no session to resolve them in.
     */
    public record Issued(String token, String username, List<String> authorities, LocalDateTime lastLogin) {}

    /** Starts a new family for a user who has just logged in. */
    @Transactional
    public String startSession(User user) {
        Instant now = clock.instant();
        return issue(user, UUID.randomUUID().toString(), now, now.plus(Duration.ofDays(maxDays)));
    }

    /**
     * Trades a refresh token for the next one in its family.
     * <p>
     * {@code noRollbackFor}: the reuse branch revokes the family and <em>then</em> answers 401. The default
     * rollback on a runtime exception would take the revocation back with it, and the stolen chain would live on.
     */
    @Transactional(noRollbackFor = APIException.class)
    public Issued rotate(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new APIException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        Instant now = clock.instant();
        RefreshToken token = repository.findForUpdateByTokenHash(hash(presented))
                .orElseThrow(() -> new APIException(HttpStatus.UNAUTHORIZED, INVALID));

        if (token.getRevokedAt() != null) {
            throw new APIException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        if (!token.getExpiresAt().isAfter(now) || !token.getFamilyExpiresAt().isAfter(now)) {
            throw new APIException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        if (token.getUsedAt() != null) {
            if (now.isAfter(token.getUsedAt().plusSeconds(graceSeconds))) {
                int ended = repository.revokeFamily(token.getFamilyId(), now);
                log.warn("Refresh token of user {} presented again after it was retired; family revoked ({} tokens)",
                        token.getUser().getId(), ended);
                throw new APIException(HttpStatus.UNAUTHORIZED, INVALID);
            }
        } else {
            token.setUsedAt(now);
        }

        // A disabled account keeps its sessions out, with the same 403 the login gives. Asked after the token
        // proved to be a live one, so a stranger cannot use the answer to learn which accounts are disabled.
        User user = token.getUser();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new APIException(HttpStatus.FORBIDDEN, "Account is disabled!");
        }

        String next = issue(user, token.getFamilyId(), now, token.getFamilyExpiresAt());
        List<String> authorities = user.getRoles().stream().map(role -> role.getRolename().name()).toList();
        return new Issued(next, user.getUsername(), authorities, user.getLastLogin());
    }

    /** Ends the session a token belongs to (logout). A token nobody knows is not an error. */
    @Transactional
    public void revokeFamilyOf(String presented) {
        if (presented == null || presented.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(presented))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** Ends every session of a user, as after a password change. */
    @Transactional
    public void revokeAllOf(Long userId) {
        repository.revokeAllOfUser(userId, clock.instant());
    }

    /** The cookie that carries a refresh token. */
    public ResponseCookie cookie(String token) {
        return base(token).maxAge(Duration.ofDays(idleDays)).build();
    }

    /** The cookie that removes it. */
    public ResponseCookie removalCookie() {
        return base("").maxAge(0).build();
    }

    public String cookieName() {
        return cookieName;
    }

    /** Nightly sweep of what can no longer do anything. */
    @Scheduled(cron = "0 19 8,20 * * *")
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        int removed = repository.deleteDead(now, now.minus(KEEP_USED));
        if (removed > 0) {
            log.info("Removed {} dead refresh tokens", removed);
        }
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .path(cookiePath)
                // Strict: this cookie is only ever needed by a call the application's own page makes.
                .sameSite("Strict");
    }

    private String issue(User user, String familyId, Instant now, Instant familyExpiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant idleEnd = now.plus(Duration.ofDays(idleDays));
        RefreshToken row = new RefreshToken();
        row.setUser(user);
        row.setTokenHash(hash(value));
        row.setFamilyId(familyId);
        row.setIssuedAt(now);
        row.setFamilyExpiresAt(familyExpiresAt);
        row.setExpiresAt(idleEnd.isBefore(familyExpiresAt) ? idleEnd : familyExpiresAt);
        repository.save(row);
        return value;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
