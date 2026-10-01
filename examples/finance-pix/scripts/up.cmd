@echo off
rem Sobe o finance-pix (Trace2Local na stack alvo) so com o Docker Desktop.
rem Duplo clique, ou no terminal: examples\finance-pix\scripts\up.cmd [-SkipBuild] [-Down] [-CaBundle C:\certs\empresa.pem]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0up.ps1" %*
echo.
pause
