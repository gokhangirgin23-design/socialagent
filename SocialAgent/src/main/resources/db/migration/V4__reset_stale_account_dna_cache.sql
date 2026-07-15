-- İstek1 fix'inden (AccountDnaCacheService) önce hesap adı/sektör/alt sektör değişiminde
-- user_account_dna cache'i hiç invalidate edilmiyordu — yani bu migration'dan önce üretilmiş
-- her aktif DNA kaydı, kullanıcının O ANKİ sektörüne/hesabına değil, DNA ilk üretildiğindeki
-- (artık eski olabilecek) sektöre/hesaba göredir. Fix yalnızca BUNDAN SONRAKİ değişiklikleri
-- yakalar; halihazırda aktif olan yanlış-bağlamlı kayıtları temizlemez. Bu yüzden mevcut tüm
-- aktif kayıtlar bir kerelik pasife alınır — her kullanıcının bir sonraki içerik üretimi güncel
-- hesabına/sektörüne göre DNA'yı otomatik yeniden üretir (bkz. ContentPipelineService.resolveAccountDna).
UPDATE user_account_dna SET active = 0, updated_date = now() WHERE active = 1;
