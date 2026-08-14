#!/bin/bash

# Root yetkisi kontrolü
if [ "$EUID" -ne 0 ]
  then echo "Lütfen bu betiği root (sudo) yetkisiyle çalıştırın."
  exit
fi

PROXY_IP=${1:-"192.168.43.1"}
PROXY_PORT=${2:-"10808"}

if [ "$(uname)" == "Darwin" ]; then
    echo "macOS üzerinde TTL değeri 65 olarak ayarlanıyor..."
    sysctl -w net.inet.ip.ttl=65
    
    echo "Wi-Fi arayüzü için SOCKS proxy yapılandırılıyor ($PROXY_IP:$PROXY_PORT)..."
    networksetup -setsocksfirewallproxy Wi-Fi $PROXY_IP $PROXY_PORT off
    networksetup -setsocksfirewallproxystate Wi-Fi on
else
    echo "Linux üzerinde TTL değeri 65 olarak ayarlanıyor..."
    iptables -t mangle -A POSTROUTING -j TTL --ttl-set 65
    
    echo "GNOME için sistem proxy yapılandırılıyor (Eğer masaüstü ortamı GNOME ise)..."
    sudo -u $SUDO_USER gsettings set org.gnome.system.proxy mode 'manual'
    sudo -u $SUDO_USER gsettings set org.gnome.system.proxy.socks host "$PROXY_IP"
    sudo -u $SUDO_USER gsettings set org.gnome.system.proxy.socks port $PROXY_PORT
fi

echo "Kurulum tamamlandı. TTL uygulandı ve proxy ayarlandı."
