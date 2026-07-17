param(
    [string]$Root = "$env:USERPROFILE\Downloads\PhoneShare",
    [int]$Port = 8088
)

$ErrorActionPreference = "Stop"
$resolvedRoot = [System.IO.Path]::GetFullPath($Root)
New-Item -ItemType Directory -Force -Path $resolvedRoot | Out-Null
Add-Type -AssemblyName System.Web

function Resolve-SafePath {
    param([string]$Relative)
    if ([string]::IsNullOrWhiteSpace($Relative)) { return $resolvedRoot }
    $clean = [System.Web.HttpUtility]::UrlDecode($Relative).Replace("/", "\").TrimStart("\")
    $full = [System.IO.Path]::GetFullPath((Join-Path $resolvedRoot $clean))
    if (-not $full.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path outside shared root"
    }
    return $full
}

function Parse-Query {
    param([string]$Url)
    $out = @{}
    $qAt = $Url.IndexOf("?")
    if ($qAt -lt 0) { return $out }
    $query = $Url.Substring($qAt + 1)
    foreach ($pair in $query.Split("&")) {
        if ([string]::IsNullOrWhiteSpace($pair)) { continue }
        $kv = $pair.Split("=", 2)
        $key = [System.Web.HttpUtility]::UrlDecode($kv[0])
        $val = if ($kv.Length -gt 1) { [System.Web.HttpUtility]::UrlDecode($kv[1]) } else { "" }
        $out[$key] = $val
    }
    return $out
}

function Send-Bytes {
    param($Stream, [byte[]]$Bytes, [string]$Type = "text/plain; charset=utf-8", [int]$Status = 200)
    $reason = if ($Status -eq 200) { "OK" } elseif ($Status -eq 404) { "Not Found" } elseif ($Status -eq 400) { "Bad Request" } else { "Error" }
    $header = "HTTP/1.1 $Status $reason`r`nContent-Type: $Type`r`nContent-Length: $($Bytes.Length)`r`nConnection: close`r`n`r`n"
    $hb = [System.Text.Encoding]::ASCII.GetBytes($header)
    $Stream.Write($hb, 0, $hb.Length)
    if ($Bytes.Length -gt 0) { $Stream.Write($Bytes, 0, $Bytes.Length) }
}

function Send-Text {
    param($Stream, [string]$Text, [int]$Status = 200)
    Send-Bytes $Stream ([System.Text.Encoding]::UTF8.GetBytes($Text)) "text/plain; charset=utf-8" $Status
}

function Read-Request {
    param($Stream)
    $buffer = New-Object byte[] 8192
    $data = New-Object System.Collections.Generic.List[byte]
    while ($true) {
        $n = $Stream.Read($buffer, 0, $buffer.Length)
        if ($n -le 0) { break }
        for ($i = 0; $i -lt $n; $i++) { $data.Add($buffer[$i]) }
        $bytes = $data.ToArray()
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $headerEnd = $text.IndexOf("`r`n`r`n")
        if ($headerEnd -ge 0) {
            $headersText = $text.Substring(0, $headerEnd)
            $headers = @{}
            $lines = $headersText.Split("`r`n")
            $requestLine = $lines[0]
            for ($j = 1; $j -lt $lines.Length; $j++) {
                $ix = $lines[$j].IndexOf(":")
                if ($ix -gt 0) { $headers[$lines[$j].Substring(0, $ix).Trim().ToLowerInvariant()] = $lines[$j].Substring($ix + 1).Trim() }
            }
            $contentLength = if ($headers.ContainsKey("content-length")) { [int]$headers["content-length"] } else { 0 }
            $bodyStart = $headerEnd + 4
            while (($data.Count - $bodyStart) -lt $contentLength) {
                $n = $Stream.Read($buffer, 0, $buffer.Length)
                if ($n -le 0) { break }
                for ($i = 0; $i -lt $n; $i++) { $data.Add($buffer[$i]) }
            }
            $all = $data.ToArray()
            $body = New-Object byte[] $contentLength
            if ($contentLength -gt 0) { [Array]::Copy($all, $bodyStart, $body, 0, $contentLength) }
            return @{ RequestLine = $requestLine; Headers = $headers; Body = $body }
        }
    }
    return $null
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
$listener.Start()
Write-Host "LAN File Share server radi na portu $Port"
Write-Host "Folder: $resolvedRoot"
Write-Host "Za gasenje pritisni Ctrl+C"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $req = Read-Request $stream
            if ($null -eq $req) { continue }
            $parts = $req.RequestLine.Split(" ")
            $method = $parts[0]
            $url = $parts[1]
            $pathOnly = $url.Split("?")[0].Trim("/")
            $query = Parse-Query $url

            if ($pathOnly -eq "ping") {
                Send-Text $stream "LANFS $env:COMPUTERNAME"
            } elseif ($pathOnly -eq "list") {
                $folder = Resolve-SafePath $query["path"]
                if (-not (Test-Path -LiteralPath $folder -PathType Container)) { Send-Text $stream "Folder not found" 404; continue }
                $lines = New-Object System.Collections.Generic.List[string]
                Get-ChildItem -LiteralPath $folder -Force | Sort-Object @{Expression="PSIsContainer";Descending=$true}, Name | ForEach-Object {
                    $rel = [System.IO.Path]::GetRelativePath($resolvedRoot, $_.FullName).Replace("\", "/")
                    if ($_.PSIsContainer) { $lines.Add("D`t$($_.Name)`t$rel") } else { $lines.Add("F`t$($_.Name)`t$rel`t$($_.Length)") }
                }
                Send-Text $stream ([string]::Join("`n", $lines))
            } elseif ($pathOnly -eq "download") {
                $file = Resolve-SafePath $query["path"]
                if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { Send-Text $stream "File not found" 404; continue }
                $bytes = [System.IO.File]::ReadAllBytes($file)
                Send-Bytes $stream $bytes "application/octet-stream" 200
            } elseif ($pathOnly -eq "upload" -and $method -eq "POST") {
                $folder = Resolve-SafePath $query["path"]
                New-Item -ItemType Directory -Force -Path $folder | Out-Null
                $name = [System.IO.Path]::GetFileName($query["name"])
                if ([string]::IsNullOrWhiteSpace($name)) { $name = "upload.bin" }
                $target = [System.IO.Path]::GetFullPath((Join-Path $folder $name))
                if (-not $target.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) { Send-Text $stream "Bad file name" 400; continue }
                [System.IO.File]::WriteAllBytes($target, [byte[]]$req.Body)
                Send-Text $stream "OK"
            } else {
                Send-Text $stream "Not found" 404
            }
        } catch {
            try { Send-Text $stream $_.Exception.Message 500 } catch {}
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}

