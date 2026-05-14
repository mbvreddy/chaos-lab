#!/bin/bash

# Build script for heap-oom-liberty application

set -e

echo "=========================================="
echo "Building Heap OOM Liberty Application"
echo "=========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed${NC}"
    exit 1
fi

# Check if Docker is installed (optional)
DOCKER_AVAILABLE=false
if command -v docker &> /dev/null; then
    DOCKER_AVAILABLE=true
fi

# Parse command line arguments
BUILD_TYPE=${1:-"all"}
IMAGE_TAG=${2:-"latest"}

build_maven() {
    echo -e "${YELLOW}Building with Maven...${NC}"
    mvn clean package
    echo -e "${GREEN}✓ Maven build completed${NC}"
}

build_docker() {
    if [ "$DOCKER_AVAILABLE" = false ]; then
        echo -e "${RED}Error: Docker is not installed${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Building Docker image with Semeru runtime...${NC}"
    docker build -f Dockerfile.semeru -t heap-oom-liberty:${IMAGE_TAG} .
    echo -e "${GREEN}✓ Docker image built: heap-oom-liberty:${IMAGE_TAG}${NC}"
}

case "$BUILD_TYPE" in
    maven)
        build_maven
        ;;
    docker)
        build_docker
        ;;
    all)
        build_maven
        build_docker
        ;;
    *)
        echo "Usage: $0 [maven|docker|all] [image-tag]"
        echo "  maven  - Build with Maven only"
        echo "  docker - Build Docker image only (requires Maven build first)"
        echo "  all    - Build both Maven and Docker (default)"
        echo "  image-tag - Docker image tag (default: latest)"
        exit 1
        ;;
esac

echo -e "${GREEN}=========================================="
echo "Build completed successfully!"
echo "==========================================${NC}"

