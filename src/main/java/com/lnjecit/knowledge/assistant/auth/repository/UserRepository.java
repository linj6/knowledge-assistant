package com.lnjecit.knowledge.assistant.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.mapper.UserMapper;
import org.springframework.stereotype.Repository;

/** 只提供用户数据查询，不承担注册、密码校验或授权。 */
@Repository
public class UserRepository {
    private final UserMapper mapper;

    public UserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    /** 新建用户，唯一约束冲突交给业务层处理。 */
    public void insert(User user) {
        mapper.insert(user);
    }

    /** 按标识查询用户，不存在时返回空。 */
    public User find(Long id) {
        return mapper.selectById(id);
    }

    /** 按数据库大小写敏感规则查询唯一用户名。 */
    public User findByUsername(String username) {
        return mapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }
}
