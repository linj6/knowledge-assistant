package com.lnjecit.knowledge.assistant.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lnjecit.knowledge.assistant.auth.entity.User;
import com.lnjecit.knowledge.assistant.auth.mapper.UserMapper;
import org.springframework.stereotype.Repository;

/** 封装用户数据读写。 */
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

    /** 在调用方事务内锁定用户行。 */
    public boolean lockById(long userId) {
        return mapper.selectOne(Wrappers.<User>lambdaQuery()
                        .select(User::getId)
                        .eq(User::getId, userId)
                        .last("FOR UPDATE"))
                != null;
    }

    public boolean updateMemoryEnabled(long userId, boolean enabled) {
        return mapper.update(
                        Wrappers.<User>lambdaUpdate().eq(User::getId, userId).set(User::getMemoryEnabled, enabled))
                > 0;
    }
}
