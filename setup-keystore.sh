#!/bin/bash

# eanalizer-mobile Keystore Setup Script
# Use this to generate a production key for Google Play and GitHub Actions.

set -e

KEYSTORE_FILE="eanalizer.jks"
ALIAS="eanalizer"
BASE64_FILE="eanalizer.jks.base64"

echo "--- eAnalizer Keystore Setup ---"

if [ -f "$KEYSTORE_FILE" ]; then
    echo "Error: $KEYSTORE_FILE already exists. Remove it first if you want to regenerate."
    exit 1
fi

read -sp "Enter password for the new keystore: " KEY_PASS
echo
read -sp "Confirm password: " KEY_PASS_CONFIRM
echo

if [ "$KEY_PASS" != "$KEY_PASS_CONFIRM" ]; then
    echo "Error: Passwords do not match."
    exit 1
fi

echo "Generating keystore..."
keytool -genkey -v -keystore "$KEYSTORE_FILE" -keyalg RSA -keysize 2048 -validity 10000 \
  -alias "$ALIAS" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
  -dname "CN=eAnalizer, O=TheUndefined, C=PL"

echo "Converting to Base64 for GitHub Secrets..."
base64 -w 0 "$KEYSTORE_FILE" > "$BASE64_FILE"

echo "----------------------------------------------------"
echo "SUCCESS!"
echo "1. Your key file: $KEYSTORE_FILE (Keep this safe/backup!)"
echo "2. Base64 file for GitHub: $BASE64_FILE"
echo
echo "NOW: Go to GitHub Repo Settings -> Secrets and variables -> Actions"
echo "Add these 4 secrets:"
echo "  RELEASE_KEYSTORE          -> (content of $BASE64_FILE)"
echo "  RELEASE_KEYSTORE_PASSWORD -> (your password)"
echo "  RELEASE_KEY_ALIAS         -> $ALIAS"
echo "  RELEASE_KEY_PASSWORD      -> (your password)"
echo "----------------------------------------------------"
