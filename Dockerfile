# ============================================
# Stage 1: Build Angular frontend
# ============================================
FROM node:20-slim AS frontend-build

WORKDIR /app/frontend

COPY frontend/limit-cache-dashboard/package.json frontend/limit-cache-dashboard/package-lock.json ./
RUN npm ci

COPY frontend/limit-cache-dashboard/ ./
RUN npx ng build --configuration production

# ============================================
# Stage 2: Build Spring Boot backend
# ============================================
FROM maven:3.9-eclipse-temurin-17 AS backend-build

WORKDIR /app/backend

COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B

COPY backend/src ./src
RUN mvn clean package -DskipTests -B

# ============================================
# Stage 3: Runtime — nginx + Java
# ============================================
FROM eclipse-temurin:17-jre

# Install nginx and supervisor
RUN apt-get update && \
    apt-get install -y --no-install-recommends nginx supervisor && \
    rm -rf /var/lib/apt/lists/*

# Copy nginx config
COPY nginx.conf /etc/nginx/sites-available/default

# Copy Angular build output
COPY --from=frontend-build /app/frontend/dist/limit-cache-dashboard/browser /usr/share/nginx/html

# Copy Spring Boot JAR
COPY --from=backend-build /app/backend/target/limit-cache-poc-*.jar /app/app.jar

# Copy supervisord config
COPY supervisord.conf /etc/supervisor/conf.d/app.conf

EXPOSE 80

CMD ["supervisord", "-n"]
