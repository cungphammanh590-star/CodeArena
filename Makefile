# CodeArena development helpers
# Usage: make infra-up | make up | make llm | ...

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
COMPOSE := docker compose -f $(ROOT)/docker-compose.yml
COMPOSE_INFRA := docker compose -f $(ROOT)/docker-compose.infra.yml
JAVA_HOME ?= $(shell ls -d $(HOME)/.local/jdk/jdk-21*/Contents/Home $(HOME)/.local/jdk/jdk-21* 2>/dev/null | head -1)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:/opt/homebrew/bin:$(PATH)

.PHONY: local-infra-up qdrant-local infra-up up down web-dev gateway business llm build help install-web redis-stack obs-up obs-down

help:
	@echo "Targets:"
	@echo "  local-infra-up  Start host-native postgres + redis-stack + qdrant (no Docker)"
	@echo "  qdrant-local    Download/start host-native qdrant on :6333"
	@echo "  infra-up     Start postgres + redis-stack + qdrant"
	@echo "  redis-stack  Start local brew redis-stack on :6380 (RedisJSON)"
	@echo "  up           Start full stack (infra + apps) via Docker"
	@echo "  down         Stop compose stack"
	@echo "  obs-up       Start lightweight observability (Prometheus/Grafana/Loki/Promtail)"
	@echo "  obs-down     Stop lightweight observability"
	@echo "  web-dev      Run Vue Vite frontend (http://127.0.0.1:5173)"
	@echo "  gateway      Run Spring Cloud Gateway locally (:8080)"
	@echo "  business     Run business-service locally (:8090)"
	@echo "  llm          Run llm-service with uvicorn (:8091)"
	@echo "  build        Build compose images"
	@echo "  install-web  npm install in apps/web"

redis-stack:
	bash "$(ROOT)/scripts/redis-stack-up.sh"

qdrant-local:
	bash "$(ROOT)/scripts/qdrant-local-up.sh"

local-infra-up:
	bash "$(ROOT)/scripts/local-infra-up.sh"

infra-up:
	$(COMPOSE_INFRA) up -d

up:
	$(COMPOSE) up -d

down:
	-$(COMPOSE) --profile observability down
	-$(COMPOSE_INFRA) down

obs-up:
	$(COMPOSE) --profile observability up -d prometheus grafana loki promtail

obs-down:
	-$(COMPOSE) --profile observability stop prometheus grafana loki promtail

install-web:
	cd "$(ROOT)/apps/web" && npm install

web-dev: install-web
	cd "$(ROOT)/apps/web" && npm run dev -- --host 127.0.0.1 --port 5173

gateway:
	cd "$(ROOT)/apps/gateway" && mvn -q spring-boot:run

business:
	set -a; [ -f "$(ROOT)/.env" ] && . "$(ROOT)/.env"; set +a; \
	cd "$(ROOT)/apps/business-service" && mvn -q spring-boot:run -Dspring-boot.run.profiles=local

llm:
	cd "$(ROOT)/apps/llm-service" && \
		(test -d .venv || python3 -m venv .venv) && \
		. .venv/bin/activate && \
		pip install -q -r requirements.txt && \
		set -a; [ -f "$(ROOT)/.env" ] && . "$(ROOT)/.env"; set +a; \
		uvicorn app.main:app --host 0.0.0.0 --port 8091 --reload

build:
	$(COMPOSE) build
