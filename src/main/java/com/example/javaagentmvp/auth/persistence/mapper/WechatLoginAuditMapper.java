package com.example.javaagentmvp.auth.persistence.mapper;

import com.example.javaagentmvp.auth.persistence.model.WechatLoginAuditRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WechatLoginAuditMapper {

    int insert(
            @Param("openid") String openid,
            @Param("loginStatus") String loginStatus,
            @Param("failureReason") String failureReason,
            @Param("ip") String ip,
            @Param("userAgent") String userAgent,
            @Param("requestId") String requestId);

    List<WechatLoginAuditRow> listRecent(@Param("limit") int limit);
}
