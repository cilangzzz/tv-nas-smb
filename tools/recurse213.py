import io, subprocess, sys
ps = r'''
$root = '\\192.168.31.213\公共存储-2581876371'
Get-ChildItem -LiteralPath $root -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
  $rel = $_.FullName.Substring($root.Length + 1)
  if ($_.PSIsContainer) { Write-Output ("D " + $rel) }
  else { Write-Output ("F " + $rel + " | " + [math]::Round($_.Length/1MB,1) + "MB") }
}
Write-Output "DONE"
'''
open(r'D:\tv\smb\tools\recurse213.ps1', 'wb').write(ps.encode('gbk'))
out = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                      '-File', r'D:\tv\smb\tools\recurse213.ps1'],
                     capture_output=True, timeout=180)
data = out.stdout
print(data.decode('gbk', 'replace'))
if out.stderr: sys.stdout.buffer.write(out.stderr[:500])
