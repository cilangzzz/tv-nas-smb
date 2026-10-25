
$p = '\\192.168.31.213\¹«¹²´æ´¢-2581876371'
Write-Output ("EXISTS: " + (Test-Path -LiteralPath $p))
try {
  $items = Get-ChildItem -LiteralPath $p -ErrorAction Stop | Select-Object -First 8
  Write-Output ("ITEMS: " + @($items).Count)
  $items | ForEach-Object { Write-Output ("ITEM: " + $_.Name) }
} catch { Write-Output ("ERR: " + $_.Exception.Message) }
