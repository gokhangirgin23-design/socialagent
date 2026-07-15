package com.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.api.dto.AddOwnAccountRequest;
import com.api.dto.UserSocialAccountDto;
import com.api.dto.repository.MonitoredAccountRepository;
import com.api.dto.repository.UserMonitoredAccountRepository;
import com.api.dto.repository.UserSocialAccountRepository;
import com.api.entity.UserSocialAccount;
import com.api.mapper.UserSocialAccountMapper;

/**
 * AccountService için Spring'siz birim testi.
 *
 * Doğrulanan davranış (istek1.md): hesap adı gerçekten değiştiğinde eski hesaba göre
 * üretilmiş Brand DNA cache'i pasife alınmalı (social_account_id aynı UUID kaldığından
 * cache key kendiliğinden değişmiyor); aynı adla "değiştirme" ise ve hesap silindiğinde de
 * cache invalidate edilmeli.
 */
@SuppressWarnings("unchecked")
class AccountServiceTest {

	private JdbcTemplate jdbcTemplate;
	private UserSocialAccountRepository userSocialAccountRepository;
	private MonitoredAccountRepository monitoredAccountRepository;
	private UserMonitoredAccountRepository userMonitoredAccountRepository;
	private UserSocialAccountMapper userSocialAccountMapper;
	private AccountDnaCacheService accountDnaCacheService;
	private AccountService service;

	@BeforeEach
	void setUp() {
		jdbcTemplate = mock(JdbcTemplate.class);
		userSocialAccountRepository = mock(UserSocialAccountRepository.class);
		monitoredAccountRepository = mock(MonitoredAccountRepository.class);
		userMonitoredAccountRepository = mock(UserMonitoredAccountRepository.class);
		userSocialAccountMapper = mock(UserSocialAccountMapper.class);
		accountDnaCacheService = mock(AccountDnaCacheService.class);
		service = new AccountService(jdbcTemplate, userSocialAccountRepository, monitoredAccountRepository,
				userMonitoredAccountRepository, userSocialAccountMapper, accountDnaCacheService);

		when(userSocialAccountRepository.save(any(UserSocialAccount.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(userSocialAccountMapper.toDto(any())).thenReturn(new UserSocialAccountDto());
	}

	@Test
	void hesapAdiDegisinceDnaCacheInvalidateEdilir() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID socialAccountId = UUID.randomUUID();
		stubExistingAccountLookup(userId, socialAccountId, "eskiHesap");

		AddOwnAccountRequest req = new AddOwnAccountRequest();
		req.setPlatform("instagram");
		req.setAccountName("yeniHesap");

		service.addOwnAccount(userId, req);

		verify(accountDnaCacheService, times(1)).invalidateAccountDnaCache(userId);
	}

	@Test
	void hesapAdiDegisincaAyniKullaniciPlatformIcinFazlaAktifSatirlarTemizlenir() throws Exception {
		// D2 kendi-onarımı: yarış durumu/eski veri nedeniyle "existing" dışında başka aktif satır
		// kalmışsa (bkz. V5 migration'ın açıklaması), addOwnAccount her çağrıda bunu temizler —
		// aksi halde resolveOwnSocialAccountId gibi sorgular hâlâ eski hesabı seçebilir.
		UUID userId = UUID.randomUUID();
		UUID socialAccountId = UUID.randomUUID();
		stubExistingAccountLookup(userId, socialAccountId, "eskiHesap");

		AddOwnAccountRequest req = new AddOwnAccountRequest();
		req.setPlatform("instagram");
		req.setAccountName("yeniHesap");

		service.addOwnAccount(userId, req);

		verify(jdbcTemplate).update(anyString(), any(), eq(userId), eq("INSTAGRAM"), eq(socialAccountId));
	}

	@Test
	void hesapAdiAyniKalirsaDnaCacheInvalidateEdilmez() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID socialAccountId = UUID.randomUUID();
		stubExistingAccountLookup(userId, socialAccountId, "ayniHesap");

		AddOwnAccountRequest req = new AddOwnAccountRequest();
		req.setPlatform("instagram");
		req.setAccountName("ayniHesap");

		service.addOwnAccount(userId, req);

		verify(accountDnaCacheService, never()).invalidateAccountDnaCache(any());
	}

	@Test
	void hesapSilinincaDnaCacheInvalidateEdilir() {
		UUID userId = UUID.randomUUID();

		// removeOwnAccount artık toplu UPDATE kullanıyor (D2 kendi-onarımı — birden fazla aktif
		// satır kalmışsa hepsini pasife alır); en az 1 satır etkilendiyse başarı sayılır.
		when(jdbcTemplate.update(anyString(), any(), eq(userId))).thenReturn(1);

		service.removeOwnAccount(userId);

		verify(accountDnaCacheService, times(1)).invalidateAccountDnaCache(userId);
	}

	@Test
	void aktifHesapYoksaSilmeNotFoundFirlatirVeDnaCacheDokunulmaz() {
		UUID userId = UUID.randomUUID();

		when(jdbcTemplate.update(anyString(), any(), eq(userId))).thenReturn(0);

		org.junit.jupiter.api.Assertions.assertThrows(
				com.api.common.ApiException.class, () -> service.removeOwnAccount(userId));

		verify(accountDnaCacheService, never()).invalidateAccountDnaCache(any());
	}

	/**
	 * addOwnAccount içindeki iki native sorguyu (aynı-ad kontrolü + mevcut aktif kayıt) SQL
	 * metnine göre ayırt ederek stub'lar: aynı-ad kontrolü her zaman boş döner (DUPLICATE
	 * atılmasın diye), mevcut aktif kayıt sorgusu ise verilen accountName ile bir satır döner.
	 */
	private void stubExistingAccountLookup(UUID userId, UUID socialAccountId, String existingAccountName) throws SQLException {
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
				.thenAnswer(invocation -> {
					String sql = invocation.getArgument(0);
					RowMapper<Object> mapper = invocation.getArgument(1);
					if (sql.contains("account_name = ?")) {
						// Aynı-ad kontrolü: aktif çakışma yok
						return List.of();
					}
					// Mevcut aktif kayıt (platform bazlı, hesap adı bağımsız) sorgusu
					return List.of(mapper.mapRow(mockAccountRow(socialAccountId, userId, existingAccountName), 0));
				});
	}

	private ResultSet mockAccountRow(UUID socialAccountId, UUID userId, String accountName) throws SQLException {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("user_social_account_id", UUID.class)).thenReturn(socialAccountId);
		when(rs.getObject("user_id", UUID.class)).thenReturn(userId);
		when(rs.getString("platform")).thenReturn("INSTAGRAM");
		when(rs.getString("account_name")).thenReturn(accountName);
		when(rs.getString("profile_url")).thenReturn("https://www.instagram.com/" + accountName + "/");
		when(rs.getObject("active", Integer.class)).thenReturn(1);
		when(rs.getTimestamp("created_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		when(rs.getTimestamp("updated_date")).thenReturn(java.sql.Timestamp.valueOf(LocalDateTime.now()));
		return rs;
	}
}
