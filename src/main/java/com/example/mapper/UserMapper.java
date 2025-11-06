package com.example.mapper;
import com.example.config.WeChatConfig;
import com.example.dto.UserInfoDTO;
import com.example.dto.UserUpdateDTO;
import com.example.entity.User;
import com.example.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.example.entity.User;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    @Select("SELECT * FROM user WHERE open_id = #{openId}")
    User findByOpenId(@Param("openId") String openId);

    @Insert("INSERT INTO user(open_id, nick_name, avatar_url) VALUES(#{openId}, #{nickName}, #{avatarUrl})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    void updateUserInfo(@Param("nickName") String nickName,
                        @Param("avatarUrl") String avatarUrl,
                        @Param("userId") Long userId);

    @Select("SELECT nick_name, avatar_url FROM user WHERE id = #{userId}")
    UserInfoDTO findById(Long userId);
}