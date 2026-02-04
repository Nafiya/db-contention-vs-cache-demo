#!/bin/bash

# ===========================================
# Limit Cache POC - Startup Script
# ===========================================

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo -e "${BLUE}"
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║     🚀 Limit Cache POC - SQL Performance Enhancement      ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check prerequisites
echo -e "${YELLOW}Checking prerequisites...${NC}"

if ! command_exists docker; then
    echo -e "${RED}Docker is not installed. Please install Docker first.${NC}"
    exit 1
fi

if ! command_exists docker-compose; then
    echo -e "${RED}Docker Compose is not installed. Please install Docker Compose first.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Prerequisites met${NC}"

# Start infrastructure
echo -e "\n${YELLOW}Starting infrastructure (PostgreSQL, Redis)...${NC}"
cd "$PROJECT_DIR/docker"
docker-compose up -d postgres redis

# Wait for services to be healthy
echo -e "\n${YELLOW}Waiting for services to be ready...${NC}"

# Wait for PostgreSQL
echo -n "Waiting for PostgreSQL..."
for i in {1..30}; do
    if docker-compose exec -T postgres pg_isready -U limituser -d limitdb >/dev/null 2>&1; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 1
done

# Wait for Redis
echo -n "Waiting for Redis..."
for i in {1..30}; do
    if docker-compose exec -T redis redis-cli ping >/dev/null 2>&1; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 1
done

# Optional: Start dev tools
if [ "$1" == "--dev" ]; then
    echo -e "\n${YELLOW}Starting development tools (Adminer, Redis Commander)...${NC}"
    docker-compose --profile dev up -d
fi

# Optional: Start monitoring
if [ "$1" == "--monitoring" ] || [ "$2" == "--monitoring" ]; then
    echo -e "\n${YELLOW}Starting monitoring stack (Prometheus, Grafana)...${NC}"
    docker-compose --profile monitoring up -d
fi

# Show status
echo -e "\n${GREEN}Infrastructure is ready!${NC}"
echo ""
echo "Services:"
echo "  • PostgreSQL: localhost:5432"
echo "  • Redis: localhost:6379"

if [ "$1" == "--dev" ]; then
    echo "  • Adminer (DB UI): http://localhost:8086"
    echo "  • Redis Commander: http://localhost:8085"
fi

if [ "$1" == "--monitoring" ] || [ "$2" == "--monitoring" ]; then
    echo "  • Prometheus: http://localhost:9090"
    echo "  • Grafana: http://localhost:3000 (admin/admin)"
fi

echo ""
echo -e "${YELLOW}To start the application:${NC}"
echo "  cd backend && ./mvnw spring-boot:run"
echo ""
echo -e "${YELLOW}Or with Docker:${NC}"
echo "  docker-compose up -d --build app"
echo ""
echo -e "${YELLOW}API Documentation:${NC}"
echo "  http://localhost:8080/swagger-ui.html"
echo ""
echo -e "${YELLOW}Quick Test:${NC}"
echo "  curl http://localhost:8080/api/demo/quick-test"
