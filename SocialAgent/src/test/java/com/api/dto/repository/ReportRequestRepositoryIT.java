package com.api.dto.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.api.entity.ReportRequest;

/**
 * Gerçek Hibernate + H2 (Flyway migrasyonlarıyla) üzerinden ReportRequest kaydı — Mockito ile
 * mock'lanmış ReportRequestServiceTest'in YAKALAYAMADIĞI bir prod insidentinin regresyon testi:
 * credit_debited/credit_debit_attempts kolonları NOT NULL idi, entity'de Java-side default yoktu
 * (yalnızca DB DEFAULT vardı) → ReportRequestService.persistAndQueue gibi bu alanları hiç set
 * etmeyen bir servis new ReportRequest() oluşturup save ettiğinde Hibernate INSERT'e açıkça NULL
 * yazıyordu (DB DEFAULT yalnızca kolon INSERT listesinden TAMAMEN çıkarılırsa devreye girer) ve
 * NOT NULL ihlali oluşuyordu — bu üretim ortamında rapor oluşturmayı tamamen kırdı.
 *
 * @Transactional: her test sonunda ROLLBACK edilir, H2'ye kalıcı veri yazılmaz.
 * @DataJpaTest yerine tam @SpringBootTest kullanılıyor çünkü bu projenin Boot 4.0.6
 * spring-boot-test-autoconfigure'ünde bir JPA test-slice (orm/jpa) bulunmuyor.
 */
@SpringBootTest
@Transactional
@DirtiesContext
class ReportRequestRepositoryIT {

    @Autowired
    private ReportRequestRepository repo;

    @Test
    void servisinSetEtmedigiAlanlarNotNullIhlaliYaratmaz() {
        // ReportRequestService.persistAndQueue ile BİREBİR aynı şekilde: yalnızca temel alanlar
        // set edilir; credit_debited/credit_debit_attempts/active_lock_key BİLEREK set edilmez.
        ReportRequest request = new ReportRequest();
        request.setRequestId(UUID.randomUUID());
        request.setUserId(UUID.randomUUID());
        request.setReportType("NONE");
        request.setQueuePushed(0);
        request.setStatus("PENDING");
        request.setAttemptCount(0);
        request.setActive(1);
        request.setCreatedDate(LocalDateTime.now());
        request.setUpdatedDate(LocalDateTime.now());

        ReportRequest saved = repo.saveAndFlush(request);

        assertNotNull(saved.getRequestId());
        assertEquals(0, saved.getCreditDebited());
        assertEquals(0, saved.getCreditDebitAttempts());
    }
}
