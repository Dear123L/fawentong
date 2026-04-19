package com.example.mapper;

import com.example.entity.ReviewRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ReviewRecordMapper {

    @Insert("INSERT INTO review_record " +
            "(user_id, record_name, file_name, file_path, file_urls, " +
            "contract_text, risk_count, status, risk_points_json, create_time, update_time) " +
            "VALUES (#{userId}, #{recordName}, #{fileName}, #{filePath}, #{fileUrls}, " +
            "#{contractText}, #{riskCount}, #{status}, #{riskPointsJson}, now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewRecord record);

    @Update("UPDATE review_record SET " +
            "record_name = #{recordName}, status = #{status}, update_time = now() " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int updateRecord(@Param("id") Long id,
                     @Param("userId") Long userId,
                     @Param("recordName") String recordName,
                     @Param("status") String status);

    @Select("SELECT * FROM review_record WHERE user_id = #{userId} AND status != '0' ORDER BY create_time DESC")
    List<ReviewRecord> selectByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM review_record WHERE id = #{id} AND user_id = #{userId} AND status != '0'")
    ReviewRecord selectByIdAndUserId(@Param("id") Long id,
                                     @Param("userId") Long userId);

    @Update("UPDATE review_record SET status = '0' WHERE id = #{id} AND user_id = #{userId}")
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);
}