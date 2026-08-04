@echo off
set DATABASE_URL=jdbc:postgresql://aws-1-ap-south-1.pooler.supabase.com:5432/postgres
set DB_USERNAME=postgres.qryrzchzmvmrzczruqwq
set DB_PASSWORD=LX2RTwf26I2jgOCS
set JWT_SECRET=nimbusboard-super-secret-jwt-key-2026-production-ready-256bit
set SPRING_FLYWAY_ENABLED=false
set SPRING_JPA_HIBERNATE_DDL_AUTO=update
java -jar target\nimbusboard-backend-0.0.1-SNAPSHOT.jar > startup.log 2>&1
