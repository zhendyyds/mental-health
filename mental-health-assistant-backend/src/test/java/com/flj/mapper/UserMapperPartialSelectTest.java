package com.flj.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flj.entity.User;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMapperPartialSelectTest {

    @Test
    void mapsCreatedAtProjectionWithoutTreatingItAsUserId() throws SQLException {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 2, 9, 30);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("created_at");
        when(metadata.getColumnName(1)).thenReturn("created_at");
        when(metadata.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(metadata.getColumnClassName(1)).thenReturn(LocalDateTime.class.getName());
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject("created_at", LocalDateTime.class)).thenReturn(createdAt);
        when(resultSet.getLong("created_at"))
                .thenThrow(new SQLException("A DATETIME column cannot be read as a user id"));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("mapping-regression",
                new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(UserMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            List<User> users = session.getMapper(UserMapper.class).selectList(
                    new LambdaQueryWrapper<User>()
                            .select(User::getCreatedAt)
                            .eq(User::getUserType, 1)
                            .ge(User::getCreatedAt, createdAt.toLocalDate().atStartOfDay())
                            .lt(User::getCreatedAt, createdAt.toLocalDate().plusDays(1).atStartOfDay()));

            assertEquals(1, users.size());
            assertEquals(createdAt, users.get(0).getCreatedAt());
            assertNull(users.get(0).getId());
        }

        verify(resultSet).getObject("created_at", LocalDateTime.class);
        verify(resultSet, never()).getLong("created_at");
    }
}
