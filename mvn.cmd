@echo off
set "MAVEN_HOME=c:\Users\Mindit sys\tools\apache-maven-3.9.6"
set "PATH=%MAVEN_HOME%\bin;%PATH%"
"%MAVEN_HOME%\bin\mvn.cmd" %*
