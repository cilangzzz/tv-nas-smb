import io, subprocess, sys
ps = r'''
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
Walk '\\192.168.31.213\公共存储-2581876371' 0
'''
open(r'D:\tv\smb\tools\walk213.ps1', 'wb').write(ps.encode('gbk'))
out = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                      '-File', r'D:\tv\smb\tools\walk213.ps1'],
                     capture_output=True, timeout=120)
sys.stdout.buffer.write(out.stdout)
sys.stdout.buffer.write(out.stderr)
