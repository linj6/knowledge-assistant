package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 使用 PostgreSQL OTHER 参数绑定UUID。 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType type)
            throws SQLException {
        statement.setObject(index, value);
    }

    public UUID getNullableResult(ResultSet result, String name) throws SQLException {
        return result.getObject(name, UUID.class);
    }

    public UUID getNullableResult(ResultSet result, int index) throws SQLException {
        return result.getObject(index, UUID.class);
    }

    public UUID getNullableResult(CallableStatement statement, int index) throws SQLException {
        return statement.getObject(index, UUID.class);
    }
}
