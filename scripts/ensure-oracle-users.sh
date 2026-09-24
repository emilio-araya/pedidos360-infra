#!/usr/bin/env bash
set -Eeuo pipefail

: "${APP_USER:?APP_USER no está configurado}"
: "${APP_USER_PASSWORD:?APP_USER_PASSWORD no está configurado}"
: "${ORDERS_DB_USERNAME:?ORDERS_DB_USERNAME no está configurado}"
: "${ORDERS_DB_PASSWORD:?ORDERS_DB_PASSWORD no está configurado}"

TARGET_PDB="${ORACLE_DATABASE:-FREEPDB1}"
SQLPLUS="${ORACLE_HOME}/bin/sqlplus"

[[ "$TARGET_PDB" =~ ^[A-Z][A-Z0-9_$#]{1,29}$ ]] || {
  printf 'Nombre de PDB no válido para provisioning local.\n' >&2
  exit 1
}

user_exists() {
  local username="$1"
  local escaped_username="${username//\'/\'\'}"

  "$SQLPLUS" -s / as sysdba <<SQL | grep -Eq '^[[:space:]]*1[[:space:]]*$'
set heading off
set feedback off
set pagesize 0
set trimspool on
whenever sqlerror exit failure
alter session set container=${TARGET_PDB};
select count(*)
  from dba_users
 where username = upper('${escaped_username}');
exit;
SQL
}

credentials_valid() {
  local username="$1"
  local password="$2"

  printf '%s\n' \
    'whenever sqlerror exit failure' \
    'select 1 from dual;' \
    'exit;' | \
    "$SQLPLUS" -s "${username}/${password}@localhost:1521/${TARGET_PDB}" \
      >/dev/null 2>&1
}

set_user_password() {
  local username="$1"
  local password="$2"

  "$SQLPLUS" -s / as sysdba >/dev/null <<SQL
set echo off
set feedback off
whenever sqlerror exit failure
alter session set container=${TARGET_PDB};
alter user ${username} identified by "${password}";
exit;
SQL
}

ensure_user() {
  local username="$1"
  local password="$2"

  [[ "$username" =~ ^[A-Z][A-Z0-9_$#]{2,29}$ ]] || {
    printf 'Nombre de usuario Oracle no válido: %s\n' "$username" >&2
    exit 1
  }
  [[ "$password" != *'"'* && "$password" != *$'\n'* && "$password" != *$'\r'* ]] || {
    printf 'La contraseña de %s no puede contener comillas dobles ni saltos de línea.\n' \
      "$username" >&2
    exit 1
  }

  if ! user_exists "$username"; then
    createAppUser "$username" "$password" "$TARGET_PDB" >/dev/null
  elif ! credentials_valid "$username" "$password"; then
    set_user_password "$username" "$password"
  fi

  user_exists "$username" || {
    printf 'No se pudo crear o validar el usuario Oracle %s.\n' "$username" >&2
    exit 1
  }
  credentials_valid "$username" "$password" || {
    printf 'Las credenciales configuradas no permiten iniciar sesión como %s.\n' \
      "$username" >&2
    exit 1
  }

  printf 'Usuario Oracle listo: %s\n' "$username"
}

ensure_user "$APP_USER" "$APP_USER_PASSWORD"
ensure_user "$ORDERS_DB_USERNAME" "$ORDERS_DB_PASSWORD"
