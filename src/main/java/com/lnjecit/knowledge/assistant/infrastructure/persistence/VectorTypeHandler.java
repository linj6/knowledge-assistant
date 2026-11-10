package com.lnjecit.knowledge.assistant.infrastructure.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 使用 PostgreSQL OTHER 参数绑定向量文本，由 vector 列校验维度。 */
public class VectorTypeHandler extends BaseTypeHandler<String> {
    public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType type)
            throws SQLException {
        statement.setObject(index, value, Types.OTHER);
    }

    public String getNullableResult(ResultSet result, String name) throws SQLException {
        return result.getString(name);
    }

    public String getNullableResult(ResultSet result, int index) throws SQLException {
        return result.getString(index);
    }

    public String getNullableResult(CallableStatement statement, int index) throws SQLException {
        return statement.getString(index);
    }
}
