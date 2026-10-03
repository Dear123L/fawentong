package com.example.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("start insert fill ...");
        this.strictInsertFill(metaObject, "createdTime", Date::new, Date.class);
        this.strictInsertFill(metaObject, "updatedTime", Date::new, Date.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("start update fill ...");
        this.strictUpdateFill(metaObject, "updatedTime", Date::new, Date.class);
    }
}