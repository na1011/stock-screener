#!/bin/bash
source "$(dirname "$0")/env.sh"
PGOPTIONS='-c statement_timeout=0' command psql -d "$1" -v ON_ERROR_STOP=1 -c 'VACUUM (ANALYZE) indicator_value, financial_statement'
