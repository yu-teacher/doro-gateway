package com.hunnit_beasts.doro.bff.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    Optional<AuthSessionEntity> findByServiceAndSessionHash(String service, String sessionHash);

    @Modifying
    @Query("DELETE FROM AuthSessionEntity s WHERE s.service = :service AND s.sessionHash = :hash")
    int deleteByServiceAndSessionHash(@Param("service") String service, @Param("hash") String hash);

    /** 마지막 사용 시각 한 컬럼만 바꾼다(엔티티를 통째로 저장하면 그사이 회전된 토큰을 옛 값으로 덮어쓴다). */
    @Modifying
    @Query("UPDATE AuthSessionEntity s SET s.lastUsedAt = :now WHERE s.service = :service AND s.sessionHash = :hash")
    int touchLastUsed(@Param("service") String service, @Param("hash") String hash, @Param("now") Instant now);

    /** 토큰 갱신 결과만 반영한다(다른 컬럼은 건드리지 않는다). */
    @Modifying
    @Query("UPDATE AuthSessionEntity s SET s.accessTokenEnc = :access, s.accessExpiresAt = :accessExpiresAt, s.refreshTokenEnc = :refresh "
            + "WHERE s.service = :service AND s.sessionHash = :hash")
    int updateTokens(@Param("service") String service, @Param("hash") String hash, @Param("access") String access,
                     @Param("accessExpiresAt") Instant accessExpiresAt, @Param("refresh") String refresh);

    @Modifying
    @Query("DELETE FROM AuthSessionEntity s WHERE s.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);

    /** 탈퇴 익명화: 사용자의 모든 서비스의 세션(암호화된 토큰 포함)을 지운다. */
    @Modifying
    @Query("DELETE FROM AuthSessionEntity s WHERE s.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
