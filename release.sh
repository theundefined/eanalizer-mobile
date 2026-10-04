#!/bin/bash

# eanalizer-mobile Modern Release Trigger
# XXI Century approach: This script only triggers the CI/CD pipeline on GitHub.
# GitHub Actions handles build, signing, tagging, and publishing.

set -e

PROJECT_NAME="eanalizer-mobile"
DEFAULT_INITIAL_VERSION="v0.1.0"
VERSION_BUMP_TYPE="patch" # Can be major, minor, patch
MAIN_BRANCH="main"

# Function to display usage
usage() {
  echo "Usage: $0 [major|minor|patch|vX.Y.Z]"
  echo "  Bumps version in build.gradle.kts and creates a trigger commit for GitHub CI."
  exit 1
}

# Ensure we are on the main branch and it's clean
if [ "$(git rev-parse --abbrev-ref HEAD)" != "$MAIN_BRANCH" ]; then
  echo "Error: Not on the $MAIN_BRANCH branch."
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "Error: Working directory is not clean. Commit your changes first."
  exit 1
fi

echo "Updating local repository..."
git pull origin $MAIN_BRANCH

# Parse arguments
if [ "$#" -eq 1 ]; then
  if [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    NEW_VERSION_ARG="$1"
  elif [[ "$1" =~ ^(major|minor|patch)$ ]]; then
    VERSION_BUMP_TYPE="$1"
  else
    usage
  fi
elif [ "$#" -ne 0 ]; then
  usage
fi

# Fetch all tags to calculate next version
echo "Fetching latest tags..."
git fetch origin --tags

# Get the latest version tag
LAST_TAG=$(git describe --tags --abbrev=0 --match "v[0-9]*.[0-9]*.[0-9]*" 2>/dev/null || echo "")

if [ -z "$LAST_TAG" ]; then
    echo "No semantic version tags found. Starting with $DEFAULT_INITIAL_VERSION."
    CURRENT_VERSION_SEMVER="0.0.0" 
else
    CURRENT_VERSION_SEMVER=$(echo "$LAST_TAG" | sed 's/^v//')
fi

# Determine the new version
if [ -n "$NEW_VERSION_ARG" ]; then
    NEW_VERSION=$(echo "$NEW_VERSION_ARG" | sed 's/^v//')
else
    IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION_SEMVER"
    case "$VERSION_BUMP_TYPE" in
        major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
        minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
        patch) PATCH=$((PATCH + 1)) ;;
    esac
    NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
fi

NEW_TAG="v$NEW_VERSION"
echo "Target version: $NEW_TAG"

# Update version in app/build.gradle.kts
CURRENT_VERSION_IN_GRADLE=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
sed -i "s/versionName = \"${CURRENT_VERSION_IN_GRADLE}\"/versionName = \"${NEW_VERSION}\"/" app/build.gradle.kts

# Bump versionCode
CURRENT_VERSION_CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
sed -i "s/versionCode = ${CURRENT_VERSION_CODE}/versionCode = ${NEW_VERSION_CODE}/" app/build.gradle.kts

echo "Local files updated to $NEW_TAG (code $NEW_VERSION_CODE)"

# Commit and push trigger
git add app/build.gradle.kts
git commit -m "release: $NEW_TAG"
git push origin $MAIN_BRANCH

echo "----------------------------------------------------"
echo "RELEASE TRIGGERED!"
echo "Version: $NEW_TAG"
echo "GitHub Actions is now building and verifying the release."
echo "If successful, a new Tag and Release will appear automatically."
echo "Check status here: https://github.com/theundefined/$PROJECT_NAME/actions"
echo "----------------------------------------------------"
