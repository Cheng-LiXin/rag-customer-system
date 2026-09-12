package com.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.entity.GuardEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注入防护审计事件 Mapper
 */
@Mapper
public interface GuardEventMapper extends BaseMapper<GuardEvent> {
}
