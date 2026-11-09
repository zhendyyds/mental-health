package com.flj.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.flj.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
