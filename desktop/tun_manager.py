import os
import subprocess
import sys
import platform

# Bu script, HevSocks5Tun veya tun2socks gibi bir araç yardımıyla tüm trafiği Android'in SOCKS5 proxy'sine yönlendirmek için bir wrapper'dır.
# Gereksinimler: sistemde 'tun2socks' binary'sinin bulunması.

PROXY_IP = "192.168.43.1"
PROXY_PORT = "10808"
TUN_NAME = "tun0" if platform.system() != "Windows" else "wintun"

def setup_routing():
    print(f"Tüm sistem trafiği TUN arayüzü üzerinden ({PROXY_IP}:{PROXY_PORT}) yönlendiriliyor...")
    
    if platform.system() == "Windows":
        # Windows için tun2socks komutu
        cmd = [
            "tun2socks.exe",
            "-device", "tun://wintun",
            "-proxy", f"socks5://{PROXY_IP}:{PROXY_PORT}",
            "-interface", "Wi-Fi" # Tethering için kullanılan yerel arayüz
        ]
    else:
        # Linux/macOS için tun2socks komutu
        cmd = [
            "tun2socks",
            "-device", f"tun://{TUN_NAME}",
            "-proxy", f"socks5://{PROXY_IP}:{PROXY_PORT}"
        ]

    try:
        print("TUN/TAP arayüzü başlatılıyor (Çıkış yapmak için CTRL+C)...")
        # Süreci başlat ve logları konsola yazdır
        subprocess.run(cmd, check=True)
    except KeyboardInterrupt:
        print("\nKapatılıyor... Yönlendirme tabloları temizleniyor.")
        # Burada gerekirse routing tabloları eski haline getirilebilir
    except FileNotFoundError:
        print("HATA: 'tun2socks' bulunamadı! Lütfen sisteme tun2socks yükleyin ve PATH'e ekleyin.")
        print("GitHub: https://github.com/xjasonlyu/tun2socks")

if __name__ == "__main__":
    setup_routing()
