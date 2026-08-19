$ErrorActionPreference = "Stop"

$repository = "Noobhero1508/atcn-wifi"
$asset = "ATCN-Wifi-Windows-x64.exe"
$baseUrl = "https://github.com/$repository/releases/latest/download"
$installDirectory = Join-Path $env:LOCALAPPDATA "ATCN Wifi"
$destination = Join-Path $installDirectory "ATCN-Wifi.exe"
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("atcn-wifi-" + [Guid]::NewGuid())

New-Item -ItemType Directory -Force -Path $temporaryDirectory | Out-Null
try {
    $download = Join-Path $temporaryDirectory $asset
    $checksum = "$download.sha256"
    Invoke-WebRequest "$baseUrl/$asset" -OutFile $download -UseBasicParsing
    Invoke-WebRequest "$baseUrl/$asset.sha256" -OutFile $checksum -UseBasicParsing

    $expected = ((Get-Content $checksum -Raw).Trim() -split "\s+")[0].ToUpperInvariant()
    $actual = (Get-FileHash $download -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -ne $expected) {
        throw "Checksum SHA256 khong khop; da huy cai dat."
    }

    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
    Copy-Item $download $destination -Force

    $startMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
    $shortcutPath = Join-Path $startMenu "ATCN Wifi.lnk"
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $destination
    $shortcut.WorkingDirectory = $installDirectory
    $shortcut.Description = "ATCN Wifi - Auto Connect Wifi KTX ĐHQGHCM"
    $shortcut.Save()

    Write-Host "[OK] Da cai ATCN Wifi tai: $destination" -ForegroundColor Green
    Write-Host "Mo ung dung tu Start Menu: ATCN Wifi"
}
finally {
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}

