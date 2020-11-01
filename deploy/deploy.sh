#!/usr/bin/env bash

BP_MODE=${1:-DEVELOPMENT}
echo BP_MODE=${BP_MODE}

if [ "$BP_MODE" = "DEVELOPMENT" ]; then
  echo "were using the development variables, not the production ones."
fi

GIT_URI=https://github.com/bootiful-podcast/bootiful-podcast-dev.github.io.git

function read_kubernetes_secret() {
  kubectl get secret $1 -o jsonpath="{.data.$2}" | base64 --decode
}

RMQ_USER=$(read_kubernetes_secret rabbitmq-secrets RABBITMQ_DEFAULT_USER)
RMQ_PW=$(read_kubernetes_secret rabbitmq-secrets RABBITMQ_DEFAULT_PASS)

PSQL_USER=$(read_kubernetes_secret postgresql-secrets POSTGRES_USER)
PSQL_PW=$(read_kubernetes_secret postgresql-secrets POSTGRES_PASSWORD)

APP_NAME=site-generator
PROJECT_ID=${GKE_PROJECT:-pgtm-jlong}
ROOT_DIR=${GITHUB_WORKSPACE:-$(cd $(dirname $1) && pwd)}

APP_YAML=${ROOT_DIR}/deploy/site-generator.yaml
APP_SERVICE_YAML=${ROOT_DIR}/deploy/site-generator-service.yaml
SECRETS=${APP_NAME}-secrets

# TODO figure out how to get the test suite running in prod again
image_id=$(docker images -q $APP_NAME)
docker rmi -f $image_id || echo "there is not an existing image to delete..."
mvn -f ${ROOT_DIR}/pom.xml -DskipTests=true clean spring-javaformat:apply spring-boot:build-image
image_id=$(docker images -q $APP_NAME)
docker tag "${image_id}" gcr.io/${PROJECT_ID}/${APP_NAME}
docker push gcr.io/${PROJECT_ID}/${APP_NAME}
kubectl delete -f $APP_YAML || echo "could not delete the existing Kubernetes environment as described in ${APP_YAML}."
kubectl delete secrets ${SECRETS} || echo "could not delete ${SECRETS}."

kubectl apply -f <(echo "
---
apiVersion: v1
kind: Secret
metadata:
  name: ${SECRETS}
type: Opaque
stringData:
  SPRING_RABBITMQ_USERNAME: "${RMQ_USER}"
  SPRING_RABBITMQ_PASSWORD: "${RMQ_PW}"
  SPRING_RABBITMQ_HOST: rabbitmq
  SPRING_PROFILES_ACTIVE: cloud
  SPRING_DATASOURCE_USERNAME: ${PSQL_USER}
  SPRING_DATASOURCE_PASSWORD: ${PSQL_PW}
  SPRING_DATASOURCE_URL:  jdbc:postgresql://postgres:5432/bp
  GIT_URI: ${GIT_URI}
  GIT_USERNAME: ${GIT_USERNAME}
  GIT_PASSWORD: ${GIT_PASSWORD}
  BP_API_PASSWORD: ${BP_API_PASSWORD}
  BP_API_USERNAME: ${BP_API_USERNAME}
")

kubectl apply -f $APP_YAML
kubectl get service | grep $APP_NAME || kubectl apply -f $APP_SERVICE_YAML
