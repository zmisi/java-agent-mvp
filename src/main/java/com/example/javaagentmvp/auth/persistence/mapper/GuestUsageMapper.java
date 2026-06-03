package com.example.javaagentmvp.auth.persistence.mapper;

import com.example.javaagentmvp.auth.persistence.model.GuestUsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GuestUsageMapper {

    int insertIfMissing(
            @Param("userId") long userId,
            @Param("quotaLimit") int quotaLimit,
            @Param("loginLimit") int loginLimit);

    GuestUsageRecord findByUserId(@Param("userId") long userId);

    int incrementUsedCount(@Param("userId") long userId);

    int incrementLoginCount(@Param("userId") long userId);

    int insertEvent(@Param("userId") long userId, @Param("action") String action);

    int resetGuestLimits(@Param("userId") long userId);
}
