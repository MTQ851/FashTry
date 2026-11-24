package com.fashtry.backend.service.business;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fashtry.backend.bean.db.UserWallet;
import com.fashtry.backend.mapper.UserWalletMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the charge-on-success credit flow (freeze -> settle/release)
 * against a real database: MyBatis-Plus over an H2 in-memory database in MySQL mode.
 * Covers the cases from the issue: a failed task is not charged, a successful task
 * is charged exactly once, and a retry does not double-charge.
 */
class WalletDeductionServiceTest {

    private static final long USER_ID = 42L;
    private static final long TASK_ID = 7L;
    private static final String SCENE = "tryon";

    private UserWalletMapper mapper;
    private WalletDeductionService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fashtry" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            RunScript.execute(connection, new InputStreamReader(
                    Objects.requireNonNull(getClass().getResourceAsStream("/schema.sql")),
                    StandardCharsets.UTF_8));
        }
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.addMapper(UserWalletMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSession session = factory.openSession(true);
        mapper = session.getMapper(UserWalletMapper.class);
        service = new WalletDeductionService(mapper);
    }

    private void giveWallet(int totalCredits) {
        UserWallet wallet = new UserWallet();
        wallet.setUserId(USER_ID);
        wallet.setTotalCredits(totalCredits);
        wallet.setFrozenCredits(0);
        wallet.setTotalRecharged(0);
        wallet.setTotalRechargedAmount(java.math.BigDecimal.ZERO);
        wallet.setVersion(0);
        mapper.insert(wallet);
    }

    private UserWallet wallet() {
        return mapper.selectById(USER_ID);
    }

    @Test
    void failedOrCancelledTaskIsNotCharged() {
        giveWallet(100);

        assertTrue(service.freezeCredits(USER_ID, 5, SCENE, TASK_ID));
        assertEquals(100, wallet().getTotalCredits());
        assertEquals(5, wallet().getFrozenCredits());

        service.releaseFrozenCredits(USER_ID, 5, SCENE, TASK_ID);
        assertEquals(100, wallet().getTotalCredits());
        assertEquals(0, wallet().getFrozenCredits());
    }

    @Test
    void successfulTaskIsChargedExactlyOnce() {
        giveWallet(100);

        assertTrue(service.freezeCredits(USER_ID, 5, SCENE, TASK_ID));
        assertTrue(service.settleFrozenCredits(USER_ID, 5, SCENE, TASK_ID));

        assertEquals(95, wallet().getTotalCredits());
        assertEquals(0, wallet().getFrozenCredits());
    }

    @Test
    void retriedSettleDoesNotDoubleCharge() {
        giveWallet(100);

        assertTrue(service.freezeCredits(USER_ID, 5, SCENE, TASK_ID));
        assertTrue(service.settleFrozenCredits(USER_ID, 5, SCENE, TASK_ID));
        // The same task settling again (retry, duplicate callback) must not deduct twice:
        // the frozen amount no longer covers a second settle, so it declines.
        assertFalse(service.settleFrozenCredits(USER_ID, 5, SCENE, TASK_ID));

        assertEquals(95, wallet().getTotalCredits());
        assertEquals(0, wallet().getFrozenCredits());
    }

    @Test
    void freezeIsRejectedWhenBalanceCannotCoverIt() {
        giveWallet(3);

        assertFalse(service.freezeCredits(USER_ID, 5, SCENE, TASK_ID));

        assertEquals(3, wallet().getTotalCredits());
        assertEquals(0, wallet().getFrozenCredits());
    }
}
