param (
    [string]$ProxyIP = "192.168.43.1",
    [string]$ProxyPort = "10808"
)

# Yönetici ayrıcalıkları kontrolü
if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Warning "Lütfen bu betiği Yönetici olarak çalıştırın!"
    Break
}

Write-Host "TTL değeri 65 olarak ayarlanıyor (Hotspot DPI atlatması için)..."
netsh int ipv4 set glob defaultcurhoplimit=65
netsh int ipv6 set glob defaultcurhoplimit=65

Write-Host "Sistem Proxy ayarları SOCKS5 ($ProxyIP:$ProxyPort) olarak yapılandırılıyor..."
$regKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
Set-ItemProperty -Path $regKey -Name ProxyEnable -Value 1
Set-ItemProperty -Path $regKey -Name ProxyServer -Value "socks=$ProxyIP:$ProxyPort"

Write-Host "İşlem tamamlandı! Değişikliklerin etkili olması için tarayıcılarınızı yeniden başlatın."
