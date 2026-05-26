#!/usr/bin/env bash
set -euo pipefail
os_family="unsupported"
os_id=""
version_id=""
pretty_name="Unknown"

if [ -f /etc/os-release ]; then
  # shellcheck source=/dev/null
  . /etc/os-release
  os_id="${ID:-}"
  version_id="${VERSION_ID:-}"
  pretty_name="${PRETTY_NAME:-$NAME}"
  case "${ID:-}" in
    ubuntu|debian) os_family="ubuntu" ;;
    rhel|rocky|centos|almalinux|ol|openEuler|alinux|alibabacloud|anolis|tencentos|opencloudos)
      major="${VERSION_ID%%.*}"
      if [ "${ID:-}" = "alinux" ] || [ "${ID:-}" = "alibabacloud" ]; then
        # Alibaba Cloud Linux 2 -> 2.x, ALinux 3 -> 3.x (RHEL8-like)
        if [ "${major:-0}" -ge 3 ] 2>/dev/null; then major=8
        elif [ "${major:-0}" -eq 2 ] 2>/dev/null; then major=7
        fi
      fi
      if [ "${major:-0}" -ge 9 ] 2>/dev/null; then os_family="rhel9"
      elif [ "${major:-0}" -eq 8 ] 2>/dev/null; then os_family="rhel8"
      elif [ "${major:-0}" -eq 7 ] 2>/dev/null; then os_family="rhel7"
      else os_family="rhel${major}"
      fi
      ;;
  esac
fi

printf '%s\n' "{\"osFamily\":\"${os_family}\",\"osId\":\"${os_id}\",\"versionId\":\"${version_id}\",\"prettyName\":\"${pretty_name}\"}"
