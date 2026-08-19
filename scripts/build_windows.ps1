$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if ($env:pythonLocation) {
  $python = Join-Path $env:pythonLocation "python.exe"
  $pythonArguments = @()
}
elseif (Get-Command py -ErrorAction SilentlyContinue) {
  $python = (Get-Command py).Source
  $pythonArguments = @("-3")
}
else {
  $python = (Get-Command python).Source
  $pythonArguments = @()
}

& $python @pythonArguments scripts/make_icons.py
& $python @pythonArguments -m PyInstaller `
  --noconfirm `
  --clean `
  --onefile `
  --windowed `
  --name "ATCN-Wifi-Windows-x64" `
  --icon "assets/atcn-wifi.ico" `
  --add-data "assets/icon-preview.png;assets" `
  --version-file "version_info.txt" `
  wifi_app.py

New-Item -ItemType Directory -Force -Path release | Out-Null
Copy-Item dist/ATCN-Wifi-Windows-x64.exe release/ATCN-Wifi-Windows-x64.exe -Force
$hash = (Get-FileHash release/ATCN-Wifi-Windows-x64.exe -Algorithm SHA256).Hash.ToLower()
"$hash  ATCN-Wifi-Windows-x64.exe" | Set-Content release/ATCN-Wifi-Windows-x64.exe.sha256 -Encoding ascii
