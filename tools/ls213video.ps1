
Get-ChildItem -LiteralPath '\\192.168.31.213\¹«¹²´æ´¢-2581876371\video' | ForEach-Object { Write-Output ($_.Name + ' | ' + $_.Length) }
