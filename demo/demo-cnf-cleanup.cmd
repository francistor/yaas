REM Environment for GKE
call "kubectl_env.bat"
call "C:\Program Files (x86)\Google\Cloud SDK\cloud_env.bat""

REM Delete cluster
call gcloud container clusters delete yaas-cluster

timeout 30