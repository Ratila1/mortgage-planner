#!/usr/bin/env bash
# Одноразовый скрипт: создаёт публичный репозиторий, ветки main/dev и защиту веток.
# Требуется: git, gh (gh auth login выполнен).
set -euo pipefail

REPO_NAME="${1:-nosurprise}"

git init -b main
git add .
git commit -m "chore: initial monorepo skeleton"

gh repo create "$REPO_NAME" --public --source=. --remote=origin --push

git checkout -b dev
git push -u origin dev

gh repo edit --default-branch dev --delete-branch-on-merge --enable-squash-merge \
  --enable-merge-commit=false --enable-rebase-merge=false

SLUG="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

protect() {
  local branch="$1"; shift
  local checks="$1"
  gh api -X PUT "repos/$SLUG/branches/$branch/protection" --input - <<JSON
{
  "required_status_checks": { "strict": true, "contexts": $checks },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
}

protect dev  '["Backend","Frontend","Docker build"]'
protect main '["Backend","Frontend","Docker build","Branch policy"]'

echo "Готово: https://github.com/$SLUG"
