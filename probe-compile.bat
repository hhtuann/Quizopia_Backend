@echo off
setlocal
cd /d D:\Developer\quizopia-system\backend
set JAVA_HOME=C:\Users\GIGABYTE\.jdks\ms-21.0.10
set PATH=%JAVA_HOME%\bin;%PATH%
rem Recompile ProbeMain + probe test into probe-classes
javac -encoding UTF-8 @target\javac-args src\test\java\com\quizopia\backend\exam\ProbeMain.java
rem Build run classpath: probe classes + main classes + deps, as an argfile
java @target\java-run-args com.quizopia.backend.exam.ProbeMain %1
