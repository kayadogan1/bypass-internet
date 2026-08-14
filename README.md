# Bypass-Internet - Kurulum ve Kullanım Kılavuzu

Bu proje, bir Android cihazını kullanarak bilgisayarınızın tüm trafiğini (Hotspot/Tethering kotasını tetiklemeden) hücresel ağ üzerinden yönlendirmenizi sağlayan yerel bir proxy uygulamasıdır. 

## 1. Android Uygulaması (Kotlin / Foreground Service)
`android` klasöründeki proje, Android Studio ile açılabilir temel bir **Boilerplate**'dir.
- **Foreground Service:** Uygulama kapanmaması için arka planda bildirimle çalışır (`ProxyService.kt`).
- **Hücresel Veri Zorlaması:** Android `NetworkRequest` ve `bindSocket` mekanizmaları aracılığıyla, Hotspot veya USB tethering'den (yerel ağ) gelen Wi-Fi trafiğini, standart RMNET (Hücresel) arayüzüne yönlendirir. Bu şekilde operatör trafiği Tethering paketi yerine ana mobil paket olarak görür.
- **SOCKS5:** Kotlin tarafında temel bir soket dinleyicisi eklenmiştir (10808 portu). İdeal senaryoda yüksek throughput için `tun2socks` veya `Xray-core` (gomobile ile derlenmiş bir .aar) kütüphanesi entegre edilebilir.

### Nasıl Çalıştırılır?
1. `android` klasörünü Android Studio'da açın.
2. Derleyin ve Android telefonunuza yükleyin.
3. Uygulamayı açıp **"Proxy Servisini Başlat"** butonuna tıklayın.

---

## 2. PC İstemcisi (Desktop Kurulum Betikleri)
Masaüstü tarafı (PC) için, Android cihazın oluşturduğu bu SOCKS5 proxy'sine bağlanmamız ve tüm trafiği proxy üzerinden geçirirken DPI/TTL limitlerini aşmamız gerekiyor.
Bunun için `desktop` klasörü içerisindeki araçları kullanacaksınız.

### TTL Atlatma ve Proxy Ayarları (Browser-level)
Operatörler cihazın hotspot açıp açmadığını **TTL (Time To Live)** değerinin 1 eksilmesinden anlarlar. Varsayılan IP TTL değerini 65'e sabitlersek, paketler Android cihaza ulaştığında (1 düşer) 64 olur ve mobil veriden çıkarken operatör bunun direkt telefondan geldiğini zanneder.

- **Windows İçin:**
  `setup_windows.ps1` dosyasına sağ tıklayıp **PowerShell ile Yönetici Olarak Çalıştırın**.
- **macOS/Linux İçin:**
  Terminali açın ve betiği çalıştırın:
  ```bash
  sudo ./setup_unix.sh
  ```

### Tüm Sistemi TUN Üzerinden Yönlendirme (UDP+TCP)
Browser trafiği yetersiz ise (oyunlar vb. uygulamalar proxy tanımaz), işletim sisteminde sanal bir ağ bağdaştırıcısı (TUN/TAP) oluşturup tüm trafiği Android'deki SOCKS5'e akıtabilirsiniz.

- `tun_manager.py` adında bir Python sarmalayıcısı (wrapper) hazırladık.
- Bu script'i kullanmak için sisteminize [tun2socks](https://github.com/xjasonlyu/tun2socks) aracını kurmanız gerekir (Binary indirip sistem PATH'inize ekleyin).
- Daha sonra terminal üzerinden:
  ```bash
  python3 tun_manager.py
  ```
  yazarak TUN arayüzünü (örn. `wintun` veya `tun0`) aktif edebilir ve sisteminizin tüm internet trafiğini doğrudan Android cihazınıza akıtabilirsiniz.

## Gelişmiş Özellikler & DoH
Operatörlerin DNS zehirlemelerini engellemek için, masaüstü veya Android tarafına Cloudflare DoH/DoT entegrasyonu (Örn: `1.1.1.1` üzerinden HTTPS DNS sorguları) eklenebilir. Eğer Xray-core/sing-box entegre ederseniz, bu proxy yapılandırması içerisinde DoH desteği default gelmektedir.
