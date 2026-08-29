param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$FontDir = Join-Path $RepoRoot 'app\src\main\assets\fonts'
New-Item -ItemType Directory -Force -Path $FontDir | Out-Null

function Download-File {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if ((Test-Path $Destination) -and -not $Force) {
        Write-Host "Already present: $Destination"
        return
    }

    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
}

$GothicPath = Join-Path $FontDir 'gothic_nguyen_regular.ttf'
$GothicUrl = 'https://github.com/TKYKmori/Gothic-Nguyen/raw/refs/heads/main/Gothic%20Nguyen%20Regular.ttf'
$GothicGitBlob = '7edfe73d9b730e3ae3422fd5d8c7bd73b8b9ac18'

Download-File -Url $GothicUrl -Destination $GothicPath
$ActualGothicBlob = (& git hash-object -- $GothicPath).Trim().ToLowerInvariant()
if ($ActualGothicBlob -ne $GothicGitBlob) {
    Remove-Item $GothicPath -Force -ErrorAction SilentlyContinue
    throw "Gothic Nguyen integrity check failed. Expected Git blob $GothicGitBlob, got $ActualGothicBlob"
}
Write-Host 'Verified Gothic Nguyen.'

$NomNaTongPath = Join-Path $FontDir 'nom_na_tong_regular.otf'
$NomNaTongUrl = 'https://github.com/nomfoundation/font/releases/download/v5.17/NomNaTong-Regular.otf'
$NomNaTongSha256 = '8c1819185482f53395341cd99e806bfb57a11d5caf9cb1ab2637e0d7186290fb'

Download-File -Url $NomNaTongUrl -Destination $NomNaTongPath
$ActualNomNaTongSha256 = (Get-FileHash -Path $NomNaTongPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ActualNomNaTongSha256 -ne $NomNaTongSha256) {
    Remove-Item $NomNaTongPath -Force -ErrorAction SilentlyContinue
    throw "Nom Na Tong integrity check failed. Expected SHA-256 $NomNaTongSha256, got $ActualNomNaTongSha256"
}
Write-Host 'Verified Nom Na Tong v5.17.'

Write-Host ''
Write-Host 'Optional Nôm fonts installed:'
Write-Host "  $GothicPath"
Write-Host "  $NomNaTongPath"
Write-Host ''
Write-Host 'Rebuild/reinstall the Android app to package them into the APK.'
