package com.example.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("test_post")
public class TestPost implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    private String coverImage;

    private Integer viewCount;

    private Integer likeCount;

    private Integer favoriteCount;

    @TableField(fill = FieldFill.INSERT)
    private Date createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedTime;
}