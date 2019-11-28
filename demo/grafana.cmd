REM Setup environment
call "kubectl_env.bat"

:loop
call kubectl --namespace monitoring port-forward svc/grafana 3000
goto :loop
