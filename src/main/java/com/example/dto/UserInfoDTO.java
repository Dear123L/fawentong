package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author lhh
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserInfoDTO {
    private String nickName;
    private String avatarUrl;
}
