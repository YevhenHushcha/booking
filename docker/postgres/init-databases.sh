#!/bin/bash
# Runs once, on first start of an empty data volume.
# Each service gets its own database and its own role: a service cannot read a neighbour's tables.
# In production these would be three separate Postgres instances; one instance with three logical
# databases is the pet-project compromise, and the isolation boundary is still enforced.
set -euo pipefail

for db in users workspaces bookings; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
		CREATE ROLE ${db}_app LOGIN PASSWORD '${APP_DB_PASSWORD}';
		CREATE DATABASE ${db}_db OWNER ${db}_app;
		REVOKE ALL ON DATABASE ${db}_db FROM PUBLIC;
		GRANT CONNECT ON DATABASE ${db}_db TO ${db}_app;
	SQL
done
