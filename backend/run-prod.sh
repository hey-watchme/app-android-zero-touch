#!/bin/bash

# ZeroTouch API - Production Deployment Script

set -e

CONTAINER_NAME="zerotouch-api"
ECR_REGISTRY="754724220380.dkr.ecr.ap-southeast-2.amazonaws.com"
ECR_REPOSITORY="zerotouch-api"
AWS_REGION="ap-southeast-2"

echo "ZeroTouch API - Production Deployment"
echo "======================================"

# Step 1: Login to ECR
echo "Step 1: Logging into ECR..."
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}
echo "ECR login successful"

# Step 2: Remove old images
echo "Step 2: Removing old images..."
docker rmi ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest || true

# Step 3: Pull latest image
echo "Step 3: Pulling latest image..."
docker pull ${ECR_REGISTRY}/${ECR_REPOSITORY}:latest
echo "Image pulled successfully"

# Step 4: Stop existing container
echo "Step 4: Stopping existing container..."
docker-compose -f docker-compose.prod.yml down || true

# Step 5: Start new container
echo "Step 5: Starting new container..."
docker-compose -f docker-compose.prod.yml up -d
echo "Container started"

# Step 6: Health check
echo "Step 6: Running health check..."
sleep 5
for i in {1..12}; do
  if curl -f http://localhost:8060/health > /dev/null 2>&1; then
    echo "Health check passed (attempt $i/12)"
    break
  fi
  if [ $i -eq 12 ]; then
    echo "Health check failed after 12 attempts"
    docker logs ${CONTAINER_NAME} --tail 50
    exit 1
  fi
  echo "Attempt $i/12 failed, retrying in 5 seconds..."
  sleep 5
done

echo ""
echo "Deployment complete!"
echo "Container: ${CONTAINER_NAME}"
echo "Port: 8060"
