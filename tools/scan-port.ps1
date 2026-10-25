# Scan /24 for a given TCP port (default 5555 = network adb)
param(
    [string]$Port = 5555,
    [string]$Prefix = "192.168.31"
)
$tasks = @()
for ($i = 1; $i -le 254; $i++) {
    $ip = "$Prefix.$i"
    $c = New-Object System.Net.Sockets.TcpClient
    $t = $c.BeginConnect($ip, [int]$Port, $null, $null)
    $tasks += [pscustomobject]@{ Ip = $ip; C = $c; T = $t }
}
Start-Sleep -Milliseconds 1200
$open = @()
foreach ($x in $tasks) {
    try {
        if ($x.T.IsCompleted) {
            if ($x.C.Connected) { $open += $x.Ip }
            $x.C.Close()
        } else {
            $x.C.Close()
        }
    } catch {}
}
if ($open.Count -eq 0) { "NO_HOSTS_ON_$Port" } else { $open }
