package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.dto.NotificationDto;
import com.api.dto.repository.NotificationRepository;
import com.api.entity.Notification;
import com.api.entity.NotificationChannel;
import com.api.entity.ReferenceType;
import com.api.mapper.NotificationMapper;

/**
 * NotificationService için Spring'siz birim testi (DB/SMTP gerektirmez).
 * JdbcTemplate + repository + mapper + sender'lar mock'lanır.
 * Doğrulanan davranışlar:
 *  - notifyReportCompleted: tamamlanmış rapor varsa notification insert + mail + push.
 *  - notifyReportCompleted: tamamlanmış rapor yoksa hiçbir şey yapılmaz.
 *  - markAsRead: ownership korumalı UPDATE etkilenen satır sayısını döner.
 *  - unreadCount: okunmamış sayısı (null -> 0).
 *  - listNotifications: native sorgu + MapStruct dönüşümü.
 */
class NotificationServiceTest {

    private JdbcTemplate jdbcTemplate;
    private NotificationRepository notificationRepository;
    private NotificationMapper notificationMapper;
    private MailSender mailSender;
    private PushSender pushSender;
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        notificationRepository = org.mockito.Mockito.mock(NotificationRepository.class);
        notificationMapper = org.mockito.Mockito.mock(NotificationMapper.class);
        mailSender = org.mockito.Mockito.mock(MailSender.class);
        pushSender = org.mockito.Mockito.mock(PushSender.class);
        service = new NotificationService(jdbcTemplate, notificationRepository, notificationMapper,
                mailSender, pushSender);
    }

    @SuppressWarnings("unchecked")
    @Test
    void tamamlanmisRaporVarsaBildirimVeMailPushUretilir() {
        // loadCompletedReportTarget -> query(sql, mapper, requestId): tek UUID vararg
        NotificationService.ReportTarget target =
                new NotificationService.ReportTarget(reportId, userId, "BOTH", "user@example.com");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of(target));
        when(mailSender.send(anyString(), anyString(), anyString())).thenReturn(SendResult.ok());
        when(pushSender.send(any(), anyString(), anyString())).thenReturn(SendResult.ok());

        service.notifyReportCompleted(requestId);

        // Her rapor için kanal başına 1 satır = toplam 2 notification (MAIL + PUSH_NOTIFICATION)
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        for (Notification n : saved) {
            assertEquals(userId, n.getUserId());
            assertEquals(reportId, n.getReferenceId());
            assertEquals(ReferenceType.REPORT.name(), n.getReferenceType());
            assertEquals(Integer.valueOf(0), n.getIsRead());
            assertTrue(n.getMessage().contains("BOTH"));
        }
        List<String> channels = saved.stream().map(Notification::getChannel).toList();
        assertTrue(channels.contains(NotificationChannel.MAIL.name()));
        assertTrue(channels.contains(NotificationChannel.PUSH_NOTIFICATION.name()));

        verify(mailSender, times(1)).send(eq("user@example.com"), anyString(), anyString());
        verify(pushSender, times(1)).send(eq(userId), anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void tamamlanmisRaporYoksaBildirimUretilmez() {
        // loadCompletedReportTarget -> query(sql, mapper, requestId): tek UUID vararg -> boş
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(UUID.class)))
                .thenReturn(List.of());

        service.notifyReportCompleted(requestId);

        verify(notificationRepository, never()).save(any());
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
        verify(pushSender, never()).send(any(), anyString(), anyString());
    }

    @Test
    void markAsReadEtkilenenSatirSayisiniDoner() {
        // markAsRead -> update(sql, Timestamp, UUID, UUID): 3 vararg elementi
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);

        int updated = service.markAsRead(userId, UUID.randomUUID());

        assertEquals(1, updated);
    }

    @Test
    void unreadCountNullIse0Doner() {
        // unreadCount -> queryForObject(sql, Long.class, userId): tek UUID vararg
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(null);
        assertEquals(0L, service.unreadCount(userId));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(3L);
        assertEquals(3L, service.unreadCount(userId));
    }

    @SuppressWarnings("unchecked")
    @Test
    void listNotificationsNativeSorguVeMapStructDonusumu() {
        // listNotifications -> query(sql, mapper, userId, size, offset): 3 vararg elementi
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setUserId(userId);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(n));
        NotificationDto dto = new NotificationDto();
        when(notificationMapper.toDtoList(any())).thenReturn(List.of(dto));

        List<NotificationDto> result = service.listNotifications(userId, 0, 10, false);

        assertEquals(1, result.size());
        verify(notificationMapper, times(1)).toDtoList(any());
    }
}
