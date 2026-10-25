
function Walk($dir, $depth) {
  try {
    $items = Get-ChildItem -LiteralPath $dir -ErrorAction Stop
  } catch { return }
  foreach ($i in $items) {
    if ($i.PSIsContainer) {
      if ($depth -lt 2) { Write-Output ("DIR: " + $i.Name); Walk $i.FullName ($depth + 1) }
    } else {
      Write-Output ("FILE: " + $i.Name + " | " + [math]::Round($i.Length/1MB,1) + "MB")
    }
  }
}
Walk '\\192.168.31.213\¹«¹²´æ´¢-2581876371' 0
