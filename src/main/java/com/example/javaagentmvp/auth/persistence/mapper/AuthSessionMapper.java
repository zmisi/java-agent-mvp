package com.example.javaagentmvp.auth.persistence.mapper;

import com.example.javaagentmvp.auth.persistence.model.AuthSessionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface AuthSessionMapper {

    int insert(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("jwtJti") String jwtJti,
            @Param("issuedAt") Instant issuedAt,
            @Param("lastActiveAt") Instant lastActiveAt,
            @Param("expiresAt") Instant expiresAt);

    AuthSessionRecord findById(@Param("id") String id);

    int touchLastActive(@Param("id") String id, @Param("lastActiveAt") Instant lastActiveAt);

    int revoke(@Param("id") String id, @Param("revokedAt") Instant revokedAt);
}
