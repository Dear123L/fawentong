package com.example.entity;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class Favorite {
    private Long id;
    private Long userId;
    private Long postId;
    private Date createdTime;
}