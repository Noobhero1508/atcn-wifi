param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$Commit,
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][string]$ChecksumPath,
    [Parameter(Mandatory)][string]$NotesPath,
    [string]$Repository = 'Noobhero1508/atcn-wifi'
)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+-beta\.\d+$' -or $Commit -notmatch '^[0-9a-f]{40}$') {
    throw 'Supply a beta version and the full tested Git commit SHA.'
}
$atcnApk = (Resolve-Path -LiteralPath $ApkPath).Path
$atcnChecksum = (Resolve-Path -LiteralPath $ChecksumPath).Path
$atcnNotes = (Resolve-Path -LiteralPath $NotesPath).Path
$atcnAssetName = [IO.Path]::GetFileName($atcnApk)
if ($atcnAssetName -ne "ATCN-Wifi-Android-$Version-debug.apk") {
    throw 'APK filename does not match the requested version.'
}
$atcnExpected = ((Get-Content -LiteralPath $atcnChecksum -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $atcnApk -Algorithm SHA256).Hash -ne $atcnExpected) {
    throw 'APK checksum does not match.'
}

# Use the existing Git login. Capture credentials in memory; never print or persist them.
$atcnCredentialOutput = "protocol=https`nhost=github.com`n`n" | git credential fill
if ($LASTEXITCODE -ne 0) { throw 'GitHub login unavailable; sign in with Git Credential Manager first.' }
$atcnCredentials = @{}
foreach ($atcnLine in $atcnCredentialOutput) {
    $atcnPair = $atcnLine -split '=', 2
    if ($atcnPair.Length -eq 2) { $atcnCredentials[$atcnPair[0]] = $atcnPair[1] }
}
if (-not $atcnCredentials['password']) { throw 'GitHub credential missing.' }
$atcnHeaders = @{
    Authorization = 'Bearer ' + $atcnCredentials['password']
    Accept = 'application/vnd.github+json'
    'User-Agent' = 'ATCN-Wifi-Android-Publisher'
    'X-GitHub-Api-Version' = '2022-11-28'
}
$atcnApi = "https://api.github.com/repos/$Repository"
$atcnTag = "android-v$Version"
$atcnRelease = $null
try {
    $atcnRelease = Invoke-RestMethod "$atcnApi/releases/tags/$atcnTag" -Headers $atcnHeaders
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
}
if ($atcnRelease) { throw 'This version already has a release. Use a new version; existing assets are never overwritten.' }

$atcnTagRef = $null
try {
    $atcnTagRef = Invoke-RestMethod "$atcnApi/git/ref/tags/$atcnTag" -Headers $atcnHeaders
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
}
if ($atcnTagRef -and $atcnTagRef.object.sha -ne $Commit) {
    throw 'Existing tag points to another commit; refusing to move it.'
}
if (-not $atcnTagRef) {
    $atcnRefBody = @{ ref = "refs/tags/$atcnTag"; sha = $Commit } | ConvertTo-Json
    $null = Invoke-RestMethod "$atcnApi/git/refs" -Headers $atcnHeaders -Method Post -ContentType 'application/json' -Body $atcnRefBody
}
$atcnReleaseBody = @{
    tag_name = $atcnTag
    target_commitish = $Commit
    name = "ATCN Wifi Android $Version"
    body = Get-Content -LiteralPath $atcnNotes -Raw
    draft = $true
    prerelease = $true
    make_latest = 'false'
} | ConvertTo-Json -Depth 4
$atcnRelease = Invoke-RestMethod "$atcnApi/releases" -Headers $atcnHeaders -Method Post -ContentType 'application/json' -Body $atcnReleaseBody
$atcnUploadBase = $atcnRelease.upload_url -replace '\{.*$', ''
foreach ($atcnFile in @($atcnApk, $atcnChecksum)) {
    $atcnName = [IO.Path]::GetFileName($atcnFile)
    $atcnAsset = Invoke-RestMethod ($atcnUploadBase + '?name=' + [Uri]::EscapeDataString($atcnName)) `
        -Headers $atcnHeaders -Method Post -ContentType 'application/octet-stream' -InFile $atcnFile
    if ($atcnAsset.size -ne (Get-Item -LiteralPath $atcnFile).Length) { throw 'Uploaded asset size mismatch; release remains a draft.' }
    if ($atcnName -eq $atcnAssetName -and $atcnAsset.digest -and $atcnAsset.digest -ne ('sha256:' + $atcnExpected.ToLowerInvariant())) {
        throw 'GitHub APK digest differs; release remains a draft.'
    }
}
$atcnPublishBody = @{ draft = $false; prerelease = $true; make_latest = 'false' } | ConvertTo-Json
$atcnPublished = Invoke-RestMethod "$atcnApi/releases/$($atcnRelease.id)" -Headers $atcnHeaders -Method Patch -ContentType 'application/json' -Body $atcnPublishBody
$atcnCredentials.Clear()
$atcnHeaders.Remove('Authorization')
Write-Output $atcnPublished.html_url
