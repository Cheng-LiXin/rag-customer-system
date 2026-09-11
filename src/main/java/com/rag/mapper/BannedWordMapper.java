package com.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.entity.BannedWord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 违禁词表
 */
@Mapper
public interface BannedWordMapper extends BaseMapper<BannedWord> {
}
