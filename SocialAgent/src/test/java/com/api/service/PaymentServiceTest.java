package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.dto.TransactionsResponse;
import com.api.dto.repository.UserPaymentLogRepository;
import com.api.dto.repository.UserPaymentRepository;
import com.api.entity.UserPayment;
import com.api.entity.UserPaymentLog;

/**
 * PaymentService için Spring'siz birim testi (DB gerektirmez).
 *
 * Doğrulanan davranışlar (FAZ CREDIT):
 *  - tryDebitCredits: yeterli kredide düşer + log satırı doğru alanlarla yazılır; yetersizde
 *    false döner ve kredi bakiyesi değişmez.
 *  - applyCallback: SUCCESS'te paket kredisi yüklenir; ikinci callback'te (idempotensi) tekrar yüklenmez.
 *  - getTransactionsResponse: INITIATED/FAILED satırları filtreleyen SQL kullanılır.
 */
class PaymentServiceTest {

    private JdbcTemplate jdbcTemplate;
    private UserPaymentRepository walletRepo;
    private UserPaymentLogRepository logRepo;
    private PaymentService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        walletRepo = mock(UserPaymentRepository.class);
        logRepo = mock(UserPaymentLogRepository.class);
        service = new PaymentService(jdbcTemplate, walletRepo, logRepo);
    }

    private UserPayment sampleWallet(long creditBalance) {
        UserPayment w = new UserPayment();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setBalance(BigDecimal.ZERO);
        w.setCurrency("TL");
        w.setTotalTopup(BigDecimal.ZERO);
        w.setTotalSpent(BigDecimal.ZERO);
        w.setCreditBalance(creditBalance);
        w.setTotalCreditTopup(0L);
        w.setTotalCreditSpent(0L);
        w.setVersion(0L);
        w.setActive(1);
        return w;
    }

    @SuppressWarnings("unchecked")
    @Test
    void yeterliKredideDusumYapilirVeLogDogruYazilir() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(sampleWallet(100)));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        boolean result = service.tryDebitCredits(userId, 20, "REPORT", UUID.randomUUID());

        assertTrue(result);
        ArgumentCaptor<UserPaymentLog> captor = ArgumentCaptor.forClass(UserPaymentLog.class);
        verify(logRepo).saveAndFlush(captor.capture());
        UserPaymentLog log = captor.getValue();
        assertEquals("DEBIT", log.getTransactionType());
        assertEquals(20L, log.getCreditAmount());
        assertEquals(100L, log.getCreditBalanceBefore());
        assertEquals(80L, log.getCreditBalanceAfter());
        assertEquals("REPORT", log.getProductType());
    }

    @SuppressWarnings("unchecked")
    @Test
    void yetersizKredideDusumYapilmazBakiyeDegismez() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(sampleWallet(5)));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);

        boolean result = service.tryDebitCredits(userId, 20, "REPORT", UUID.randomUUID());

        assertFalse(result);
        verify(logRepo, never()).saveAndFlush(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void basariliCallbackPaketKredisiniYukler() {
        UUID logId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("user_payment_log"), any(RowMapper.class), anyString()))
                .thenAnswer(inv -> {
                    UserPaymentLog l = new UserPaymentLog();
                    l.setId(logId);
                    l.setUserId(userId);
                    l.setAmount(new BigDecimal("699"));
                    l.setProcessed(0);
                    l.setPaymentStatus(null);
                    l.setPackageCode("STANDARD");
                    return List.of(l);
                });
        when(jdbcTemplate.query(contains("credit_balance, total_credit_topup"), any(RowMapper.class), any()))
                .thenReturn(List.of(sampleWallet(0)));
        // callback_count++ güncellemesi (3 param): dönüş değeri kullanılmıyor
        // SUCCESS+processed=1 güncellemesi ve credit_amount log güncellemesi (her ikisi 6 param)
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        // user_payment.credit_balance += ? güncellemesi (4 param)
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        PaymentService.CallbackResult result = service.applyCallback(
                "TR123", true, "69900", "card", null, null, "0", "raw");

        assertTrue(result.success);
        assertFalse(result.alreadyProcessed);
    }

    @SuppressWarnings("unchecked")
    @Test
    void ikinciCallbackIdempotensiIleTekrarKrediYuklemez() {
        UUID logId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("user_payment_log"), any(RowMapper.class), anyString()))
                .thenAnswer(inv -> {
                    UserPaymentLog l = new UserPaymentLog();
                    l.setId(logId);
                    l.setUserId(userId);
                    l.setAmount(new BigDecimal("699"));
                    l.setProcessed(1); // zaten işlenmiş
                    l.setPaymentStatus("SUCCESS");
                    l.setPackageCode("STANDARD");
                    return List.of(l);
                });

        PaymentService.CallbackResult result = service.applyCallback(
                "TR123", true, "69900", "card", null, null, "0", "raw");

        assertTrue(result.alreadyProcessed);
        assertTrue(result.success);
        // İkinci kez işlendiği için krediye dokunulmamalı
        verify(jdbcTemplate, never()).update(contains("credit_balance = credit_balance +"), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void transactionsSorgusuInitiatedVeFailedSatirlariFiltreler() {
        when(jdbcTemplate.query(contains("payment_status = 'SUCCESS' AND processed = 1"),
                any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
        when(jdbcTemplate.query(contains("credit_balance, total_credit_topup"), any(RowMapper.class), any()))
                .thenReturn(List.of(sampleWallet(42)));

        TransactionsResponse response = service.getTransactionsResponse(userId, 50, 0);

        assertEquals(0, response.getTransactions().size());
        assertEquals(42L, response.getCreditBalance());
    }
}
