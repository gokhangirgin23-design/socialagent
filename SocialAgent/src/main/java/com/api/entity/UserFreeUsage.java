package com.api.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Kullanıcı başına ücretsiz ilk kullanım hakkını takip eden tablo (V11 migration).
 * Kredi sistemine (user_payment) hiç dokunmaz — tamamen ayrı, kendi kendine yeten bir kontrol.
 * Her kullanıcı için tek satır (user_id PK): 1 rapor + (o rapora bağlı) 1 post/story hakkı.
 */
@Entity
@Table(name = "user_free_usage")
@Getter
@Setter
public class UserFreeUsage {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    // Ücretsiz rapor kullanıldı mı? (0/1)
    @Column(name = "free_report_used")
    private Integer freeReportUsed = 0;

    // Ücretsiz üretilen rapor isteğinin id'si (report_request.request_id) — içerik hakkının
    // hangi rapora bağlı olduğunu belirlemek için kullanılır
    @Column(name = "free_report_request_id")
    private UUID freeReportRequestId;

    @Column(name = "free_report_used_date")
    private LocalDateTime freeReportUsedDate;

    // Ücretsiz içerik (post/story) kullanıldı mı? (0/1)
    @Column(name = "free_content_used")
    private Integer freeContentUsed = 0;

    @Column(name = "free_content_id")
    private UUID freeContentId;

    @Column(name = "free_content_used_date")
    private LocalDateTime freeContentUsedDate;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}
