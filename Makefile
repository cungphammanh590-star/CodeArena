# CodeArena development helpers
# Usage: make infra-up | make up | make llm | ...

ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
COMPOSE := docker compose -f $(ROOT)/docker-compose.yml
COMPOSE_INFRA := docker compose -f $(ROOT)/docker-compose.infra.yml
JAVA_HOME ?= $(shell ls -d $(HOME)/.local/jdk/jdk-21*/Contents/Home $(HOME)/.local/jdk/jdk-21* 2>/dev/null | head -1)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:/opt/homebrew/bin:$(PATH)

.PHONY: infra-up up down web-dev gateway business llm build help install-web

help:
	@echo "Targets:"
	@echo "  infra-up   Start postgres + redis + nacos only"
	@echo "  up         Start full stack (infra + apps) via Docker"
	@echo "  down       Stop compose stack"
	@echo "  web-dev    Run Vue Vite frontend (http://localhost:5173)"
	@echo "  gateway    Run Spring Cloud Gateway locally (:8080)"
	@echo "  business   Run business-service locally (:8090)"
	@echo "  llm        Run llm-service with uvicorn (:8091)"
	@echo "  build      Build compose images"
	@echo "  install-web  npm install in apps/web"

infra-up:
	$(COMPOSE_INFRA) up -d

up:
	$(COMPOSE) up -d --build

down:
	-$(COMPOSE) --profile observability down
	-$(COMPOSE_INFRA) down

install-web:
	cd "$(ROOT)/apps/web" && npm install

web-dev: install-web
	cd "$(ROOT)/apps/web" && npm run dev

gateway:
	cd "$(ROOT)/apps/gateway" && mvn -q spring-boot:run

business:
	set -a; [ -f "$(ROOT)/.env" ] && . "$(ROOT)/.env"; set +a; \
	cd "$(ROOT)/apps/business-service" && mvn -q spring-boot:run

llm:
	cd "$(ROOT)/apps/llm-service" && \
		(test -d .venv || python3 -m venv .venv) && \
		. .venv/bin/activate && \
		pip install -q -r requirements.txt && \
		uvicorn app.main:app --host 0.0.0.0 --port 8091 --reload

build:
	$(COMPOSE) build
