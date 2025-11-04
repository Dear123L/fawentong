package com.example.util;

import lombok.Data;

/**
 * @author lhh
 * 响应格式
 */
@Data
public class JsonResponse {
    private int code;
    private String msg;
    private Object data;

    // 私有化构造方法
    private JsonResponse() {
    }

    public static JsonResponse success(String msg) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(ResultCode.SUCCESS);
        jsonResponse.setMsg(msg);
        return jsonResponse;

    }

    public static JsonResponse success(String msg, Object object) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(ResultCode.SUCCESS);
        jsonResponse.setMsg(msg);
        jsonResponse.setData(object);
        return jsonResponse;
    }

    public static JsonResponse fail(int code, String msg) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(code);
        jsonResponse.setMsg(msg);
        return jsonResponse;
    }

    public static JsonResponse paramError(String msg) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(ResultCode.PARAM_ERROR);
        jsonResponse.setMsg(msg);
        return jsonResponse;
    }

    public static JsonResponse authError(String msg) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(ResultCode.UNAUTHORIZED);
        jsonResponse.setMsg(msg);
        return jsonResponse;
    }

    public static JsonResponse systemError(String msg) {
        JsonResponse jsonResponse = new JsonResponse();
        jsonResponse.setCode(ResultCode.SYSTEM_ERROR);
        jsonResponse.setMsg(msg);
        return jsonResponse;
    }
}
