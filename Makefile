COMMIT_HASH := $(shell git rev-parse --short HEAD)

deploy:
	COMMIT_HASH=$(COMMIT_HASH) docker compose up -d --build --force-recreate --remove-orphans

log:
	docker logs -f kijaro --tail 1000
