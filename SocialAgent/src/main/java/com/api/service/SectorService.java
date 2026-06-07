package com.api.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.api.common.ApiException;
import com.api.common.DataResponse;
import com.api.common.ResponseCode;
import com.api.dto.SectorDto;
import com.api.dto.SubsectorDto;
import com.api.dto.repository.UserInfoRepository;
import com.api.entity.Sector;
import com.api.entity.Subsector;
import com.api.entity.UserInfo;
import com.api.mapper.SectorMapper;
import com.api.mapper.SubsectorMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sektör ve alt sektör iş mantığı (concrete; interface yok — CLAUDE.md Madde 1).
 * Lookup'lar JdbcTemplate native (text-block SQL + ? param — airepo stili).
 * Güncelleme işlemleri JPA save ile yapılır.
 * Türkçe yorum çoğu satırda (CLAUDE.md Madde 7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SectorService {

	// Native sorgular için JdbcTemplate
	private final JdbcTemplate jdbcTemplate;

	// Kullanıcı güncelleme için JPA repository
	private final UserInfoRepository userInfoRepository;

	// Sector entity -> DTO dönüştürücü
	private final SectorMapper sectorMapper;

	// Subsector entity -> DTO dönüştürücü
	private final SubsectorMapper subsectorMapper;

	// Sector satırlarını entity'ye çeviren RowMapper
	private static final RowMapper<Sector> SECTOR_ROW_MAPPER = (rs, rowNum) -> {
		Sector s = new Sector();
		// UUID alanı null güvenli çeviriliyor
		s.setSectorId(rs.getObject("sector_id", UUID.class));
		s.setName(rs.getString("name"));
		s.setActive(rs.getObject("active", Integer.class));
		if (rs.getTimestamp("created_date") != null) {
			s.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
		}
		if (rs.getTimestamp("updated_date") != null) {
			s.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
		}
		return s;
	};

	// Subsector satırlarını entity'ye çeviren RowMapper
	private static final RowMapper<Subsector> SUBSECTOR_ROW_MAPPER = (rs, rowNum) -> {
		Subsector sub = new Subsector();
		sub.setSubsectorId(rs.getObject("subsector_id", UUID.class));
		sub.setSectorId(rs.getObject("sector_id", UUID.class));
		sub.setName(rs.getString("name"));
		sub.setActive(rs.getObject("active", Integer.class));
		if (rs.getTimestamp("created_date") != null) {
			sub.setCreatedDate(rs.getTimestamp("created_date").toLocalDateTime());
		}
		if (rs.getTimestamp("updated_date") != null) {
			sub.setUpdatedDate(rs.getTimestamp("updated_date").toLocalDateTime());
		}
		return sub;
	};

	/**
	 * Aktif tüm sektörleri isim sırasıyla listeler.
	 * Endpoint: POST /sector/list (kimlik doğrulaması gerekmez)
	 */
	@Transactional(readOnly = true)
	public List<SectorDto> listSectors() {
		// Aktif sektörleri isme göre sıralı çek
		String sql = """
				SELECT sector_id, name, active, created_date, updated_date
				FROM sector
				WHERE active = 1
				ORDER BY name
				""";
		// Native sorgu ile entity listesi al
		List<Sector> sectors = jdbcTemplate.query(sql, SECTOR_ROW_MAPPER);
		// MapStruct ile DTO listesine dönüştür
		return sectorMapper.toDtoList(sectors);
	}

	/**
	 * Belirtilen sektöre ait aktif alt sektörleri isim sırasıyla listeler.
	 * Endpoint: POST /sector/listSubsectors (güvenli)
	 */
	@Transactional(readOnly = true)
	public List<SubsectorDto> listSubsectors(UUID sectorId) {
		// Verilen sektöre ait aktif alt sektörleri çek
		String sql = """
				SELECT subsector_id, sector_id, name, active, created_date, updated_date
				FROM subsector
				WHERE sector_id = ? AND active = 1
				ORDER BY name
				""";
		// ? parametresi ile native sorgu
		List<Subsector> subsectors = jdbcTemplate.query(sql, SUBSECTOR_ROW_MAPPER, sectorId);
		// MapStruct ile DTO listesine dönüştür
		return subsectorMapper.toDtoList(subsectors);
	}

	/**
	 * Kullanıcının sektörünü tek başına kaydeder (alt sektör opsiyonel).
	 * Mevcut alt sektör yeni sektöre ait değilse tutarlılık için temizlenir.
	 * Endpoint: POST /sector/saveSector (güvenli)
	 */
	@Transactional
	public DataResponse<Void> saveSector(UUID userId, UUID sectorId) {
		// 1) Sektörü native sorgu ile doğrula (aktif mi?)
		Sector sector = findActiveSectorById(sectorId);
		if (sector == null) {
			throw new ApiException(ResponseCode.NOT_FOUND, "Sektör bulunamadı: " + sectorId);
		}

		// 2) Kullanıcıyı JPA ile getir
		UserInfo user = userInfoRepository.findById(userId)
				.orElseThrow(() -> new ApiException(ResponseCode.NOT_FOUND, "Kullanıcı bulunamadı"));

		// 3) Sektörü set et
		user.setSectorId(sectorId);

		// 4) Mevcut alt sektör bu sektöre ait değilse temizle (alt sektör opsiyonel kalır)
		if (user.getSubsectorId() != null) {
			Subsector current = findActiveSubsectorById(user.getSubsectorId());
			if (current == null || !sectorId.equals(current.getSectorId())) {
				user.setSubsectorId(null);
			}
		}

		user.setUpdatedDate(LocalDateTime.now());
		userInfoRepository.save(user);

		// 5) Veri döndürülmez, yalnızca başarı kodu
		return DataResponse.of(ResponseCode.SUCCESS);
	}

	/**
	 * Kullanıcının alt sektörünü kaydeder.
	 * Alt sektörün, kullanıcının seçili sektörüne ait olduğu kontrol edilir.
	 * Endpoint: POST /sector/saveSubsector (güvenli)
	 */
	@Transactional
	public DataResponse<Void> saveSubsector(UUID userId, UUID subsectorId) {
		// 1) Alt sektörü native sorgu ile doğrula
		Subsector subsector = findActiveSubsectorById(subsectorId);
		if (subsector == null) {
			throw new ApiException(ResponseCode.NOT_FOUND, "Alt sektör bulunamadı: " + subsectorId);
		}

		// 2) Kullanıcıyı JPA ile getir
		UserInfo user = userInfoRepository.findById(userId)
				.orElseThrow(() -> new ApiException(ResponseCode.NOT_FOUND, "Kullanıcı bulunamadı"));

		// 3) Sektörü alt sektörden türet; subsector'ın ait olduğu sektör otomatik set edilir
		user.setSectorId(subsector.getSectorId());

		// 4) Alt sektörü set et ve kaydet
		user.setSubsectorId(subsectorId);
		user.setUpdatedDate(LocalDateTime.now());
		userInfoRepository.save(user);

		// 5) Veri döndürülmez, yalnızca başarı kodu
		return DataResponse.of(ResponseCode.SUCCESS);
	}

	// ============================================================
	// Yardımcı native lookup'lar
	// ============================================================

	/**
	 * Aktif sektörü id ile getirir; bulunamazsa null döner.
	 */
	private Sector findActiveSectorById(UUID sectorId) {
		String sql = """
				SELECT sector_id, name, active, created_date, updated_date
				FROM sector
				WHERE sector_id = ? AND active = 1
				""";
		List<Sector> rows = jdbcTemplate.query(sql, SECTOR_ROW_MAPPER, sectorId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Aktif alt sektörü id ile getirir; bulunamazsa null döner.
	 */
	private Subsector findActiveSubsectorById(UUID subsectorId) {
		String sql = """
				SELECT subsector_id, sector_id, name, active, created_date, updated_date
				FROM subsector
				WHERE subsector_id = ? AND active = 1
				""";
		List<Subsector> rows = jdbcTemplate.query(sql, SUBSECTOR_ROW_MAPPER, subsectorId);
		return rows.isEmpty() ? null : rows.get(0);
	}
}
