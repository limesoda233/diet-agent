package com.diet.mapper;

import com.diet.model.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedbackMapper {
    int ownsSession(@Param("userId") Long userId, @Param("sessionId") String sessionId);
    int canReferenceItem(@Param("userId") Long userId, @Param("itemId") Long itemId);
    int ownsTrace(@Param("userId") Long userId, @Param("traceId") String traceId);
    int insert(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("itemId") Long itemId,
            @Param("action") String action,
            @Param("rating") Integer rating,
            @Param("reason") String reason
    );

    List<FeedbackRow> findBySessions(
            @Param("userId") Long userId,
            @Param("sessionIds") List<String> sessionIds,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );
}
