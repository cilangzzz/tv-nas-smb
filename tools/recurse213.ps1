
$root = '\\192.168.31.213\¹«¹²´æ´¢-2581876371'
Get-ChildItem -LiteralPath $root -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
  $rel = $_.FullName.Substring($root.Length + 1)
  if ($_.PSIsContainer) { Write-Output ("D " + $rel) }
  else { Write-Output ("F " + $rel + " | " + [math]::Round($_.Length/1MB,1) + "MB") }
}
Write-Output "DONE"
