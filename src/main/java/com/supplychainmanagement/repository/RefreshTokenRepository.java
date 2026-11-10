package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The token behind a hash, locked: a refresh checks that it is unused and then retires it, and two
     * requests with the same token must not both pass the check. The user comes along because the refresh
     * reads its active flag right after.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.user WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findForUpdateByTokenHash(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Calls off every token of a family that still stands. Returns how many it ended. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Calls off every session of a user, as after a password change. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.user.id = :userId AND t.revokedAt IS NULL")
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Rows that can no longer do anything: past either end of their life, or retired long enough ago that
     * keeping them for the reuse check has stopped paying.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :now OR t.familyExpiresAt < :now "
            + "OR t.usedAt < :usedBefore")
    int deleteDead(@Param("now") Instant now, @Param("usedBefore") Instant usedBefore);
}
