package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.dto.repository.UserInfoRepository;
import com.api.entity.UserInfo;
import com.api.mapper.SectorMapper;
import com.api.mapper.SubsectorMapper;

/**
 * SectorService için Spring'siz birim testi.
 *
 * Doğrulanan davranış (istek1.md): sektör/alt sektör değişimi ContentPrompts.forBrandDna
 * promptuna giren bir bağlam olduğundan, kaydetme sonrası Brand DNA cache'i pasife alınmalı.
 */
class SectorServiceTest {

	private JdbcTemplate jdbcTemplate;
	private UserInfoRepository userInfoRepository;
	private SectorMapper sectorMapper;
	private SubsectorMapper subsectorMapper;
	private AccountDnaCacheService accountDnaCacheService;
	private SectorService service;

	@BeforeEach
	void setUp() {
		jdbcTemplate = mock(JdbcTemplate.class);
		userInfoRepository = mock(UserInfoRepository.class);
		sectorMapper = mock(SectorMapper.class);
		subsectorMapper = mock(SubsectorMapper.class);
		accountDnaCacheService = mock(AccountDnaCacheService.class);
		service = new SectorService(jdbcTemplate, userInfoRepository, sectorMapper, subsectorMapper, accountDnaCacheService);
	}

	@SuppressWarnings("unchecked")
	@Test
	void saveSectorSonrasiDnaCacheInvalidateEdilir() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID sectorId = UUID.randomUUID();

		when(jdbcTemplate.query(anyString(), (RowMapper<Object>) any(RowMapper.class), org.mockito.ArgumentMatchers.eq(sectorId)))
				.thenAnswer(invocation -> {
					RowMapper<Object> mapper = invocation.getArgument(1);
					return List.of(mapper.mapRow(mockSectorRow(sectorId), 0));
				});

		UserInfo user = new UserInfo();
		user.setUserId(userId);
		when(userInfoRepository.findById(userId)).thenReturn(Optional.of(user));

		service.saveSector(userId, sectorId);

		verify(accountDnaCacheService, times(1)).invalidateAccountDnaCache(userId);
	}

	@SuppressWarnings("unchecked")
	@Test
	void saveSubsectorSonrasiDnaCacheInvalidateEdilir() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID sectorId = UUID.randomUUID();
		UUID subsectorId = UUID.randomUUID();

		when(jdbcTemplate.query(anyString(), (RowMapper<Object>) any(RowMapper.class), org.mockito.ArgumentMatchers.eq(subsectorId)))
				.thenAnswer(invocation -> {
					RowMapper<Object> mapper = invocation.getArgument(1);
					return List.of(mapper.mapRow(mockSubsectorRow(subsectorId, sectorId), 0));
				});

		UserInfo user = new UserInfo();
		user.setUserId(userId);
		when(userInfoRepository.findById(userId)).thenReturn(Optional.of(user));

		service.saveSubsector(userId, subsectorId);

		verify(accountDnaCacheService, times(1)).invalidateAccountDnaCache(userId);
		assertEquals(sectorId, user.getSectorId());
	}

	private ResultSet mockSectorRow(UUID sectorId) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("sector_id", UUID.class)).thenReturn(sectorId);
		when(rs.getString("name")).thenReturn("Teknoloji");
		when(rs.getObject("active", Integer.class)).thenReturn(1);
		when(rs.getObject("display_order", Integer.class)).thenReturn(1);
		when(rs.getTimestamp("created_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		when(rs.getTimestamp("updated_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		return rs;
	}

	private ResultSet mockSubsectorRow(UUID subsectorId, UUID sectorId) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("subsector_id", UUID.class)).thenReturn(subsectorId);
		when(rs.getObject("sector_id", UUID.class)).thenReturn(sectorId);
		when(rs.getString("name")).thenReturn("Mobil Uygulama");
		when(rs.getObject("active", Integer.class)).thenReturn(1);
		when(rs.getObject("display_order", Integer.class)).thenReturn(1);
		when(rs.getTimestamp("created_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		when(rs.getTimestamp("updated_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		return rs;
	}
}
