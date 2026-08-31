#!/usr/bin/env bash
# Immediate feedback on the mistakes that this repo's build catches late, or not at all.
#
# Runs after every Write/Edit. It is a grep, not a compiler: it must stay fast enough that
# nobody is tempted to turn it off. Everything here is a pattern that is *always* wrong in
# this repository - if a rule needs judgement, it belongs in ArchUnit or in review, not here.
#
# Exit 2 returns the message to the agent so it can correct itself before moving on.
set -uo pipefail

file=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null || true)
[ -n "$file" ] || exit 0
[ -f "$file" ] || exit 0

case "$file" in
  *.java) ;;
  *pom.xml) ;;
  *) exit 0 ;;
esac

findings=()
check() { # pattern, message
  if grep -nE "$1" "$file" >/dev/null 2>&1; then findings+=("$2"); fi
}

case "$file" in
*.java)
  check 'import com\.fasterxml\.jackson' \
    "Jackson 2 import. Spring Boot 4 defaults to Jackson 3 under 'tools.jackson'."
  check '@MockBean|@SpyBean' \
    "@MockBean/@SpyBean were removed in Spring Boot 4. Use @MockitoBean/@MockitoSpyBean."
  check 'import org\.junit\.jupiter\.api\.Assertions|import org\.junit\.Assert' \
    "Assertions are AssertJ here (assertThat). JUnit's Assertions is a banned import and checkstyle will fail."
  check 'import java\.util\.Date|import java\.util\.Calendar' \
    "java.util.Date/Calendar are banned. Use java.time."
  check 'System\.(out|err)\.print' \
    "Console output is invisible to the log pipeline. Use SLF4J."
  check '\.printStackTrace\(\)' \
    "Log the exception with context instead: log.error(\"...\", ex)."
  check 'catch \((java\.lang\.)?(Throwable|Error) ' \
    "Catching Throwable/Error hides JVM failures. Catch what you can handle."
  check 'import org\.springframework\.cloud' \
    "Spring Cloud is not available on Boot 4.1 - see docs/adr/0004-do-not-adopt-spring-cloud.md."
  check '@Transactional.*readOnly = false' \
    "readOnly = false is the default; drop it."
  # Domain purity, checked by ArchUnit later but far cheaper to catch now.
  case "$file" in
    */domain/*)
      check 'import (org\.springframework|jakarta\.persistence|jakarta\.servlet|tools\.jackson|org\.hibernate)' \
        "The domain layer imports no framework (P-020). Move this to an adapter."
      ;;
  esac
  ;;
*pom.xml)
  check '<artifactId>spring-boot-starter-web</artifactId>' \
    "spring-boot-starter-web is deprecated in Boot 4. Use spring-boot-starter-webmvc."
  check '<artifactId>spring-boot-starter-aop</artifactId>' \
    "spring-boot-starter-aop was removed in Boot 4. Use spring-boot-starter-aspectj."
  check '<groupId>org\.testcontainers</groupId>[[:space:]]*<artifactId>(postgresql|junit-jupiter|kafka|rabbitmq)</artifactId>' \
    "Testcontainers 2 renamed its modules: use testcontainers-postgresql, testcontainers-junit-jupiter, and so on."
  check '<groupId>org\.springframework\.cloud</groupId>' \
    "Spring Cloud's current train targets Boot 4.0, not 4.1 - see docs/adr/0004-do-not-adopt-spring-cloud.md."
  ;;
esac

if [ ${#findings[@]} -gt 0 ]; then
  {
    echo "Repository guardrails flagged ${file}:"
    for finding in "${findings[@]}"; do echo "  - ${finding}"; done
    echo "See AGENTS.md > Non-negotiables, or the 'spring-boot-4' skill."
  } >&2
  exit 2
fi
exit 0
