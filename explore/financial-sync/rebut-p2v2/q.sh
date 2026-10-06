#!/bin/bash
# 운영 DB SELECT 전용
exec /opt/homebrew/opt/postgresql@16/bin/psql -d stock_screener -v ON_ERROR_STOP=1 -At -F $'\t' -c "BEGIN READ ONLY; $1; COMMIT;"
