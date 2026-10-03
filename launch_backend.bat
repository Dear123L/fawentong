@echo off
cd /d D:\Quanta_Back_end\back_project\fawentong
D:\ENV\jdk17\bin\java.exe -jar target\fawentong-0.0.1-SNAPSHOT.jar --server.port=8082 > log_8082.log 2>&1
