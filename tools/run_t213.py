import io, subprocess, sys
ps = r'''
$p = '\\192.168.31.213\公共存储-2581876371'
Write-Output ("EXISTS: " + (Test-Path -LiteralPath $p))
try {
  $items = Get-ChildItem -LiteralPath $p -ErrorAction Stop | Select-Object -First 8
  Write-Output ("ITEMS: " + @($items).Count)
  $items | ForEach-Object { Write-Output ("ITEM: " + $_.Name) }
} catch { Write-Output ("ERR: " + $_.Exception.Message) }
'''
open(r'D:\tv\smb\tools\t213.ps1', 'wb').write(ps.encode('gbk'))
out = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                      '-File', r'D:\tv\smb\tools\t213.ps1'],
                     capture_output=True, timeout=60)
sys.stdout.buffer.write(out.stdout)
sys.stdout.buffer.write(out.stderr)
