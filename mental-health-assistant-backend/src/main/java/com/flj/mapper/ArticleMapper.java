package com.flj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.flj.entity.Article;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
