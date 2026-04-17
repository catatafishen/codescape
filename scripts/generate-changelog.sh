#!/usr/bin/env bash
# Generate a changelog from conventional commits since CHANGELOG_BASELINE.
#
# Inputs (env):
#   CHANGELOG_BASELINE  Git ref to diff from. Defaults to the marketplace-latest tag,
#                       falling back to the most recent v* tag, then the empty tree.
#   CHANGELOG_FORMAT    "html" (default) for plugin <change-notes>, or "md" for release notes.
#
# Output: changelog text on stdout.

set -euo pipefail

FORMAT="${CHANGELOG_FORMAT:-html}"

baseline="${CHANGELOG_BASELINE:-}"
if [ -z "$baseline" ]; then
  if git rev-parse --verify -q marketplace-latest >/dev/null; then
    baseline="marketplace-latest"
  else
    baseline=$(git tag --list 'v*' --sort=-v:refname | head -n1 || true)
  fi
fi

if [ -n "$baseline" ] && git rev-parse --verify -q "$baseline" >/dev/null; then
  range="${baseline}..HEAD"
else
  range="HEAD"
fi

# Lines like: <sha> <subject>
mapfile -t commits < <(git log --no-merges --pretty=format:'%h %s' "$range" || true)

declare -a feats fixes perf docs others
for line in "${commits[@]}"; do
  sha="${line%% *}"
  subject="${line#* }"
  case "$subject" in
    feat:*|feat\(*\):*|feat!*) feats+=("$subject ($sha)") ;;
    fix:*|fix\(*\):*|fix!*)    fixes+=("$subject ($sha)") ;;
    perf:*|perf\(*\):*)        perf+=("$subject ($sha)") ;;
    docs:*|docs\(*\):*)        docs+=("$subject ($sha)") ;;
    *)                         others+=("$subject ($sha)") ;;
  esac
done

emit_section_md() {
  local title="$1"; shift
  local items=("$@")
  [ "${#items[@]}" -gt 0 ] || return 0
  echo "### $title"
  for item in "${items[@]}"; do echo "- $item"; done
  echo
}

emit_section_html() {
  local title="$1"; shift
  local items=("$@")
  [ "${#items[@]}" -gt 0 ] || return 0
  echo "<h4>$title</h4>"
  echo "<ul>"
  for item in "${items[@]}"; do
    # Escape minimal HTML special chars
    safe="${item//&/&amp;}"; safe="${safe//</&lt;}"; safe="${safe//>/&gt;}"
    echo "  <li>$safe</li>"
  done
  echo "</ul>"
}

if [ "${#commits[@]}" -eq 0 ]; then
  if [ "$FORMAT" = "html" ]; then
    echo "<p>No changes since previous release.</p>"
  else
    echo "_No changes since previous release._"
  fi
  exit 0
fi

if [ "$FORMAT" = "html" ]; then
  emit_section_html "✨ Features" "${feats[@]:-}"
  emit_section_html "🐛 Fixes"    "${fixes[@]:-}"
  emit_section_html "⚡ Performance" "${perf[@]:-}"
  emit_section_html "📖 Docs"     "${docs[@]:-}"
  emit_section_html "🔧 Other"    "${others[@]:-}"
else
  emit_section_md "✨ Features"    "${feats[@]:-}"
  emit_section_md "🐛 Fixes"       "${fixes[@]:-}"
  emit_section_md "⚡ Performance" "${perf[@]:-}"
  emit_section_md "📖 Docs"        "${docs[@]:-}"
  emit_section_md "🔧 Other"       "${others[@]:-}"
fi
