#!/usr/bin/env bash
# Creates the JS8-Tactical release signing key, once, and prints what to put in the GitHub
# repository secrets (Settings > Secrets and variables > Actions):
#   JS8_KEYSTORE_BASE64, JS8_KEYSTORE_PASSWORD, JS8_KEY_ALIAS, JS8_KEY_PASSWORD
#
# Keep js8tactical-release.jks and its password somewhere safe and backed up. Every future
# update must be signed with this same key: if it is lost, users have to uninstall (losing their
# data) to install a newer build.
#
# Needs keytool (comes with any Java JDK).
set -euo pipefail

OUT="${1:-js8tactical-release.jks}"
ALIAS="js8tactical"
if [ -e "$OUT" ]; then
  echo "$OUT already exists; not overwriting it." >&2
  exit 1
fi

read -r -s -p "Choose a keystore password (at least 6 characters): " PASS; echo
read -r -s -p "Type it again: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Passwords differ." >&2; exit 1; }
[ ${#PASS} -ge 6 ] || { echo "Too short." >&2; exit 1; }

keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=JS8-Tactical, O=S2 Underground"

echo
echo "Created $OUT. Add these repository secrets:"
echo "  JS8_KEY_ALIAS        = $ALIAS"
echo "  JS8_KEYSTORE_PASSWORD = (the password you chose)"
echo "  JS8_KEY_PASSWORD      = (the same password)"
echo "  JS8_KEYSTORE_BASE64   = the contents of ${OUT}.base64.txt (created next to the keystore)"
base64 -w0 "$OUT" > "${OUT}.base64.txt" 2>/dev/null || base64 -i "$OUT" -o "${OUT}.base64.txt"
echo
echo "Then delete ${OUT}.base64.txt, and keep $OUT and the password backed up."
echo "Publish a signed build by pushing a tag, e.g.: git tag v1.2.0-alpha.1 && git push origin v1.2.0-alpha.1"
