package com.example.javaagentmvp.auth.persistence.mapper;

import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WechatUserMapper {

    int upsert(WechatUserRecord record);

    WechatUserRecord findByOpenid(@Param("openid") String openid);

    WechatUserRecord findById(@Param("id") long id);

    List<WechatUserRecord> listAll();

    int updateRoleAndStatus(
            @Param("id") long id,
            @Param("role") String role,
            @Param("status") String status);

    int updateAvatarUrl(@Param("id") long id, @Param("avatarUrl") String avatarUrl);

    int updateNickname(@Param("id") long id, @Param("nickname") String nickname);
}
