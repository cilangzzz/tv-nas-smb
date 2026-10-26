import subprocess, sys
ps = r'''
Get-ChildItem -LiteralPath '\\192.168.31.213\公共存储-2581876371\video' | ForEach-Object { Write-Output ($_.Name + ' | ' + $_.Length) }
'''
open(r'D:\tv\smb\tools\ls213video.ps1', 'wb').write(ps.encode('gbk'))
out = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                      '-File', r'D:\tv\smb\tools\ls213video.ps1'],
                     capture_output=True, timeout=30)
sys.stdout.buffer.write(out.stdout)
sys.stdout.buffer.write(out.stderr)
